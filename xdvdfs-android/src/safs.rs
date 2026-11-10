//! A read-only [`FilesystemHierarchy`]/[`FilesystemCopier`] backed by the folder
//! manifest from the app. Directory listings come from the manifest and file
//! contents are read through file descriptors the app opens on demand.

use std::collections::HashMap;
use std::fs::File;
use std::io::{self, Seek, SeekFrom, Write};
use std::os::unix::fs::FileExt;
use std::os::unix::io::FromRawFd;

use xdvdfs::blockdev::BlockDeviceWrite;
use xdvdfs::write::fs::{FileEntry, FileType, FilesystemCopier, FilesystemHierarchy, PathRef};

use crate::host::{check_cancel, Host};
use crate::manifest::{normalize, split_parent, Entry};

const COPY_CHUNK: usize = 1024 * 1024;

pub struct SafFilesystem {
    host: Host,
    paths: Vec<String>,
    children: HashMap<String, Vec<FileEntry>>,
    files: HashMap<String, usize>,
    open: Option<(usize, File)>,
}

impl SafFilesystem {
    pub fn new(host: Host, entries: &[Entry]) -> Self {
        let mut paths = Vec::with_capacity(entries.len());
        let mut children: HashMap<String, Vec<FileEntry>> = HashMap::new();
        let mut files = HashMap::new();

        // The root always exists, even for an empty folder.
        children.entry(String::new()).or_default();

        for (index, entry) in entries.iter().enumerate() {
            paths.push(entry.path.clone());

            let (parent, name) = split_parent(&entry.path);
            let file_type = if entry.is_dir {
                children.entry(entry.path.clone()).or_default();
                FileType::Directory
            } else {
                files.insert(entry.path.clone(), index);
                FileType::File
            };

            children
                .entry(parent.to_string())
                .or_default()
                .push(FileEntry {
                    name: name.to_string(),
                    file_type,
                    len: if entry.is_dir { 0 } else { entry.size },
                });
        }

        Self {
            host,
            paths,
            children,
            files,
            open: None,
        }
    }

    fn index_of(&self, path: PathRef<'_>) -> io::Result<usize> {
        let key = normalize(&path.to_string());
        self.files.get(&key).copied().ok_or_else(|| {
            io::Error::new(io::ErrorKind::NotFound, format!("file not found: {key}"))
        })
    }

    /// Returns the (cached) open file for manifest entry `index`. Consecutive
    /// reads from the same file reuse one descriptor, which matters because the
    /// compressor asks for the data sector by sector.
    fn open_index(&mut self, index: usize) -> io::Result<&File> {
        let cached = matches!(&self.open, Some((i, _)) if *i == index);
        if !cached {
            // Close the previous file before opening the next one.
            self.open = None;

            let fd = self.host.open_source_file(index);
            if fd < 0 {
                return Err(io::Error::new(
                    io::ErrorKind::Other,
                    format!("failed to open source file {}", self.paths[index]),
                ));
            }

            // SAFETY: the app detached this descriptor and handed ownership to us.
            let file = unsafe { File::from_raw_fd(fd) };
            self.open = Some((index, file));
        }

        match &self.open {
            Some((_, file)) => Ok(file),
            None => unreachable!("file was just opened"),
        }
    }
}

impl FilesystemHierarchy for SafFilesystem {
    type Error = io::Error;

    fn read_dir(&mut self, path: PathRef<'_>) -> Result<Vec<FileEntry>, io::Error> {
        let key = normalize(&path.to_string());
        self.children.get(&key).cloned().ok_or_else(|| {
            io::Error::new(
                io::ErrorKind::NotFound,
                format!("directory not found: {key}"),
            )
        })
    }
}

impl<T> FilesystemCopier<T> for SafFilesystem
where
    T: Write + Seek + BlockDeviceWrite<WriteError = io::Error>,
{
    type Error = io::Error;

    fn copy_file_in(
        &mut self,
        src: PathRef<'_>,
        dest: &mut T,
        input_offset: u64,
        output_offset: u64,
        size: u64,
    ) -> Result<u64, io::Error> {
        let index = self.index_of(src)?;

        dest.seek(SeekFrom::Start(output_offset))?;
        if size == 0 {
            return Ok(0);
        }

        let file = self.open_index(index)?;
        let mut buf = vec![0u8; std::cmp::min(size, COPY_CHUNK as u64) as usize];
        let mut copied: u64 = 0;
        while copied < size {
            check_cancel();

            let n = std::cmp::min(buf.len() as u64, size - copied) as usize;
            file.read_exact_at(&mut buf[..n], input_offset + copied)?;
            dest.write_all(&buf[..n])?;
            copied += n as u64;
        }

        Ok(copied)
    }
}

impl FilesystemCopier<[u8]> for SafFilesystem {
    type Error = io::Error;

    fn copy_file_in(
        &mut self,
        src: PathRef<'_>,
        dest: &mut [u8],
        input_offset: u64,
        output_offset: u64,
        size: u64,
    ) -> Result<u64, io::Error> {
        let index = self.index_of(src)?;
        let file = self.open_index(index)?;

        let output_offset = output_offset as usize;
        let size = std::cmp::min(size as usize, <[u8]>::len(dest) - output_offset);
        let dest = &mut dest[output_offset..(output_offset + size)];

        // Read until the buffer is full or the file ends; anything past the end of
        // the file (sector padding) is zero.
        let mut filled = 0usize;
        while filled < size {
            let n = file.read_at(&mut dest[filled..], input_offset + filled as u64)?;
            if n == 0 {
                break;
            }
            filled += n;
        }
        dest[filled..].fill(0);

        Ok(size as u64)
    }
}
