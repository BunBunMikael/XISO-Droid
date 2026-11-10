//! Parsing of the folder manifest handed over by the Kotlin side.
//!
//! Android hands us a Storage Access Framework tree rather than a path, so the
//! app walks the tree itself and sends a flat text manifest, one entry per line:
//!
//! ```text
//! D<TAB>0<TAB>relative/dir
//! F<TAB>1234<TAB>relative/dir/file.bin
//! ```
//!
//! Paths are relative to the picked folder and use `/` as separator. The index
//! of a `F` line (counting every non-empty line) is what the native side passes
//! back to `Host.openSourceFile` to get a file descriptor for that entry.
//! This module has no dependencies so it can be unit tested on its own.

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Entry {
    pub path: String,
    pub is_dir: bool,
    pub size: u64,
}

/// Collapse a path into `a/b/c` form: no leading/trailing/duplicate separators.
pub fn normalize(path: &str) -> String {
    let mut out = String::with_capacity(path.len());
    for component in path.split('/').filter(|c| !c.is_empty()) {
        if !out.is_empty() {
            out.push('/');
        }
        out.push_str(component);
    }
    out
}

/// Splits a normalized path into (parent directory, file name).
pub fn split_parent(path: &str) -> (&str, &str) {
    match path.rfind('/') {
        Some(idx) => (&path[..idx], &path[idx + 1..]),
        None => ("", path),
    }
}

pub fn parse(text: &str) -> Result<Vec<Entry>, String> {
    let mut entries = Vec::new();
    for (line_no, line) in text.split('\n').enumerate() {
        let line = line.strip_suffix('\r').unwrap_or(line);
        if line.is_empty() {
            continue;
        }

        let mut parts = line.splitn(3, '\t');
        let kind = parts.next().unwrap_or("");
        let size = parts.next();
        let path = parts.next();
        let (size, path) = match (size, path) {
            (Some(s), Some(p)) => (s, p),
            _ => return Err(format!("malformed manifest line {}", line_no + 1)),
        };

        let is_dir = match kind {
            "D" => true,
            "F" => false,
            _ => return Err(format!("malformed manifest line {}", line_no + 1)),
        };
        let size: u64 = size
            .parse()
            .map_err(|_| format!("bad size in manifest line {}", line_no + 1))?;
        let path = normalize(path);
        if path.is_empty() {
            return Err(format!("empty path in manifest line {}", line_no + 1));
        }

        entries.push(Entry { path, is_dir, size });
    }

    Ok(entries)
}

#[cfg(test)]
mod test {
    use super::*;

    #[test]
    fn normalize_collapses_separators() {
        assert_eq!(normalize(""), "");
        assert_eq!(normalize("/"), "");
        assert_eq!(normalize("//a//b/"), "a/b");
        assert_eq!(normalize("a"), "a");
        assert_eq!(normalize("/a/b/c"), "a/b/c");
    }

    #[test]
    fn split_parent_works() {
        assert_eq!(split_parent("a"), ("", "a"));
        assert_eq!(split_parent("a/b"), ("a", "b"));
        assert_eq!(split_parent("a/b/c"), ("a/b", "c"));
    }

    #[test]
    fn parse_basic() {
        let text = "D\t0\tmedia\nF\t12\tdefault.xbe\nF\t5\tmedia/a b.xwb\n";
        let e = parse(text).unwrap();
        assert_eq!(e.len(), 3);
        assert_eq!(
            e[0],
            Entry {
                path: "media".into(),
                is_dir: true,
                size: 0
            }
        );
        assert_eq!(
            e[1],
            Entry {
                path: "default.xbe".into(),
                is_dir: false,
                size: 12
            }
        );
        assert_eq!(e[2].path, "media/a b.xwb");
    }

    #[test]
    fn parse_keeps_index_stable_across_blank_lines_is_not_required() {
        // blank lines are skipped, indices are over parsed entries
        let e = parse("\nF\t1\ta\n\nF\t2\tb\n").unwrap();
        assert_eq!(e.len(), 2);
        assert_eq!(e[1].path, "b");
    }

    #[test]
    fn parse_path_with_tab_after_third_field_is_kept() {
        let e = parse("F\t1\ta\tb").unwrap();
        assert_eq!(e[0].path, "a\tb");
    }

    #[test]
    fn parse_errors() {
        assert!(parse("X\t1\ta").is_err());
        assert!(parse("F\tabc\ta").is_err());
        assert!(parse("F\t1").is_err());
        assert!(parse("F\t1\t/").is_err());
    }

    #[test]
    fn parse_crlf() {
        let e = parse("F\t1\ta\r\nD\t0\tb\r\n").unwrap();
        assert_eq!(e[0].path, "a");
        assert_eq!(e[1].path, "b");
    }
}
