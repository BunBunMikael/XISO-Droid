//! The four operations the app offers, mirroring `xdvdfs-cli` (`pack`,
//! `unpack`, `compress`) and the `xdvdfs-web` workflows.

use std::ffi::OsStr;
use std::fs::File;
use std::io::{self, BufReader, BufWriter, Write};
use std::os::unix::fs::FileExt;
use std::os::unix::io::FromRawFd;
use std::path::PathBuf;

use anyhow::{anyhow, bail, Context, Result};

use xdvdfs::blockdev::OffsetWrapper;
use xdvdfs::write::fs::{
    DefaultCopier, PathRef, SectorLinearBlockDevice, SectorLinearBlockFilesystem,
    SectorLinearImage, StdIOCopier, XDVDFSFilesystem,
};
use xdvdfs::write::img::{create_xdvdfs_image, ProgressVisitor};

use crate::host::{
    check_cancel, Host, EV_ITEM, EV_SECT_DONE, EV_SECT_TOTAL, EV_STAGE, EV_TOTAL, ST_COMPRESSING,
    ST_FLUSH, ST_PACKING,
};
use crate::manifest;
use crate::safs::SafFilesystem;

const IO_CHUNK: usize = 1024 * 1024;
/// How many compressed sectors to process between progress callbacks.
const SECTOR_REPORT_INTERVAL: u64 = 512;

// ---------------------------------------------------------------------------
// Progress
// ---------------------------------------------------------------------------

/// Reports image creation progress to the app. Cancellation is checked on every
/// entry, which unwinds out of the image writer (see `host::check_cancel`).
struct PackProgress {
    host: Host,
    done: i64,
}

impl PackProgress {
    fn new(host: &Host) -> Self {
        Self {
            host: host.clone(),
            done: 0,
        }
    }
}

impl ProgressVisitor for PackProgress {
    fn directory_discovered(&mut self, _num_entries: usize) {
        check_cancel();
    }

    fn entry_counts(&mut self, file_count: usize, _dir_count: usize) {
        self.host.progress(EV_TOTAL, file_count as i64, 0, None);
        self.host.progress(EV_STAGE, ST_PACKING, 0, None);
    }

    fn file_added(&mut self, path: PathRef<'_>, _sector: u64) {
        check_cancel();
        self.done += 1;
        let name = path.to_string();
        self.host
            .progress(EV_ITEM, self.done, -1, Some(name.as_str()));
    }

    fn finished(&mut self) {
        self.host.progress(EV_STAGE, ST_FLUSH, 0, None);
    }
}

fn ciso_progress(host: &Host) -> impl FnMut(ciso::write::ProgressInfo) {
    let host = host.clone();
    let mut done: u64 = 0;
    move |pi| match pi {
        ciso::write::ProgressInfo::SectorCount(count) => {
            host.progress(EV_SECT_TOTAL, count as i64, 0, None);
        }
        ciso::write::ProgressInfo::SectorFinished => {
            done += 1;
            if done % SECTOR_REPORT_INTERVAL == 0 {
                check_cancel();
                host.progress(EV_SECT_DONE, done as i64, 0, None);
            }
        }
        ciso::write::ProgressInfo::Finished => {
            host.progress(EV_SECT_DONE, done as i64, 0, None);
        }
        _ => {}
    }
}

fn finish_image(mut image: BufWriter<File>) -> Result<()> {
    image.flush().context("Failed to flush the output image")?;
    // Not every document provider supports fsync on its descriptors; that's fine.
    let _ = image.get_ref().sync_all();
    Ok(())
}

// ---------------------------------------------------------------------------
// Pack
// ---------------------------------------------------------------------------

/// Pack an existing ISO / XISO (any of the XGD layouts) into a clean XISO.
pub fn pack_image(src_fd: i32, out_fd: i32, host: &Host) -> Result<()> {
    // SAFETY: both descriptors were detached by the app and are owned by us now.
    let src = unsafe { File::from_raw_fd(src_fd) };
    let out = unsafe { File::from_raw_fd(out_fd) };
    let mut image = BufWriter::with_capacity(IO_CHUNK, out);

    let source =
        OffsetWrapper::new(BufReader::new(src)).context("Failed to open xdvdfs image file")?;
    let mut fs = XDVDFSFilesystem::<_, _, StdIOCopier<_, _>>::new(source)
        .ok_or_else(|| anyhow!("Failed to open xdvdfs filesystem"))?;

    create_xdvdfs_image(&mut fs, &mut image, PackProgress::new(host))
        .context("Failed to pack image")?;

    finish_image(image)
}

/// Pack a folder (described by `manifest_text`) into an XISO.
pub fn pack_folder(manifest_text: &str, out_fd: i32, host: &Host) -> Result<()> {
    let entries = manifest::parse(manifest_text).map_err(|e| anyhow!(e))?;

    // SAFETY: the descriptor was detached by the app and is owned by us now.
    let out = unsafe { File::from_raw_fd(out_fd) };
    let mut image = BufWriter::with_capacity(IO_CHUNK, out);

    let mut fs = SafFilesystem::new(host.clone(), &entries);
    create_xdvdfs_image(&mut fs, &mut image, PackProgress::new(host))
        .context("Failed to pack image")?;

    finish_image(image)
}

// ---------------------------------------------------------------------------
// Unpack
// ---------------------------------------------------------------------------

/// Extract every file of an XISO through the app's output folder callbacks.
pub fn unpack(src_fd: i32, host: &Host) -> Result<()> {
    // SAFETY: the descriptor was detached by the app and is owned by us now.
    let src = unsafe { File::from_raw_fd(src_fd) };

    let mut img =
        OffsetWrapper::new(BufReader::new(src)).context("Failed to open xdvdfs image file")?;
    let img_offset = u64::from(img.get_offset());
    let volume = xdvdfs::read::read_volume(&mut img).context("Failed to read image volume")?;
    let tree = volume
        .root_table
        .file_tree(&mut img)
        .context("Failed to read the image directory tree")?;

    host.progress(EV_TOTAL, tree.len() as i64, 0, None);

    // Data is read with pread() so the shared file offset is never disturbed.
    let src: &File = img.get_ref().get_ref();
    let mut buf = vec![0u8; IO_CHUNK];

    for (index, (parent, node)) in tree.iter().enumerate() {
        check_cancel();

        let name = node
            .name_str()
            .map_err(|e| anyhow!("Invalid file name in image: {e}"))?;
        let rel = format!("{parent}/{name}");
        let rel = rel.trim_start_matches('/');
        let size = node.node.dirent.data.size as u64;

        if node.node.dirent.is_directory() {
            if host.create_output_dir(rel) < 0 {
                bail!("Failed to create directory {rel}");
            }
        } else if node.node.dirent.filename_length == 0 {
            // Same as the CLI: entries without a name cannot be extracted.
            host.progress(
                EV_ITEM,
                (index + 1) as i64,
                0,
                Some("<unnamed file, skipped>"),
            );
            continue;
        } else {
            let fd = host.create_output_file(rel);
            if fd < 0 {
                bail!("Failed to create file {rel}");
            }
            // SAFETY: the descriptor was detached by the app and is owned by us now.
            let mut out = unsafe { File::from_raw_fd(fd) };

            if size > 0 {
                let base = img_offset
                    + node
                        .node
                        .dirent
                        .data
                        .offset(0)
                        .map_err(|e| anyhow!("Bad data region for {rel}: {e}"))?;

                let mut copied: u64 = 0;
                while copied < size {
                    check_cancel();

                    let n = std::cmp::min(buf.len() as u64, size - copied) as usize;
                    src.read_exact_at(&mut buf[..n], base + copied)
                        .with_context(|| format!("Failed to read {rel} from the image"))?;
                    out.write_all(&buf[..n])
                        .with_context(|| format!("Failed to write {rel}"))?;
                    copied += n as u64;
                }
            }

            out.flush()
                .with_context(|| format!("Failed to flush {rel}"))?;
            let _ = out.sync_all();
        }

        host.progress(EV_ITEM, (index + 1) as i64, size as i64, Some(&*name));
    }

    host.progress(EV_STAGE, ST_FLUSH, 0, None);
    Ok(())
}

// ---------------------------------------------------------------------------
// Compress
// ---------------------------------------------------------------------------

type BufFile = BufWriter<File>;

/// Creates the CISO part files through the app's output folder.
struct HostSplitFs {
    host: Host,
}

impl ciso::split::SplitFilesystem<io::Error, BufFile> for HostSplitFs {
    fn create_file(&mut self, name: &OsStr) -> Result<BufFile, io::Error> {
        let name = name
            .to_str()
            .ok_or_else(|| io::Error::new(io::ErrorKind::InvalidInput, "non UTF-8 file name"))?;

        let fd = self.host.create_output_file(name);
        if fd < 0 {
            return Err(io::Error::new(
                io::ErrorKind::Other,
                format!("failed to create output file {name}"),
            ));
        }

        // SAFETY: the descriptor was detached by the app and is owned by us now.
        let file = unsafe { File::from_raw_fd(fd) };
        Ok(BufWriter::with_capacity(IO_CHUNK, file))
    }

    fn close(&mut self, mut file: BufFile) {
        let _ = file.flush();
        let _ = file.get_ref().sync_all();
    }
}

/// Second half of compression, shared by the folder and image sources: runs the
/// CISO writer over the sector linear image that was just laid out.
macro_rules! write_ciso {
    ($host:expr, $base_name:expr, $slbd:expr, $slbfs:expr) => {{
        $host.progress(EV_STAGE, ST_COMPRESSING, 0, None);

        let mut output = ciso::split::SplitOutput::new(
            HostSplitFs {
                host: $host.clone(),
            },
            PathBuf::from($base_name),
        );
        let mut input = SectorLinearImage::new(&$slbd, &mut $slbfs);
        ciso::write::write_ciso_image(&mut input, &mut output, ciso_progress($host))
            .context("Failed to compress image")?;

        $host.progress(EV_STAGE, ST_FLUSH, 0, None);
        output.close();
    }};
}

/// Pack a folder and compress it to CISO parts named after `base_name`
/// (for example `Game.cso`, which the splitter turns into `Game.1.cso`, ...).
pub fn compress_folder(manifest_text: &str, base_name: &str, host: &Host) -> Result<()> {
    let entries = manifest::parse(manifest_text).map_err(|e| anyhow!(e))?;

    let mut fs = SafFilesystem::new(host.clone(), &entries);
    let mut slbd = SectorLinearBlockDevice::default();
    let mut slbfs = SectorLinearBlockFilesystem::new(&mut fs);

    create_xdvdfs_image(&mut slbfs, &mut slbd, PackProgress::new(host))
        .context("Failed to pack image")?;

    write_ciso!(host, base_name, slbd, slbfs);
    Ok(())
}

type ImageDev = OffsetWrapper<BufReader<File>>;
type ImageFs<'a> = SectorLinearBlockFilesystem<
    &'a mut XDVDFSFilesystem<ImageDev, [u8], DefaultCopier<ImageDev, [u8]>>,
>;

/// Repack an existing image and compress it to CISO parts.
pub fn compress_image(src_fd: i32, base_name: &str, host: &Host) -> Result<()> {
    // SAFETY: the descriptor was detached by the app and is owned by us now.
    let src = unsafe { File::from_raw_fd(src_fd) };

    let source =
        OffsetWrapper::new(BufReader::new(src)).context("Failed to open xdvdfs image file")?;
    let mut fs =
        XDVDFSFilesystem::new(source).ok_or_else(|| anyhow!("Failed to open xdvdfs filesystem"))?;

    let mut slbd = SectorLinearBlockDevice::default();
    let mut slbfs: ImageFs = SectorLinearBlockFilesystem::new(&mut fs);

    create_xdvdfs_image(&mut slbfs, &mut slbd, PackProgress::new(host))
        .context("Failed to pack image")?;

    write_ciso!(host, base_name, slbd, slbfs);
    Ok(())
}
