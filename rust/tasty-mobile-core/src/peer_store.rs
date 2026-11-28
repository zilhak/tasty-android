//! The trusted device IDs of remote Tasty peers (the TOFU records of `tasty-net-identity`),
//! kept as one file per peer.
//!
//! A record is `<dir>/<peer>.id` and holds the device ID as 64 lowercase hex digits and one
//! newline. The first record is published without a hard link: the complete content is written
//! and synced in a temporary file in the same folder, which is then renamed onto the final name
//! with `renameat2(RENAME_NOREPLACE)`. Of several first calls for one peer exactly one renames
//! its file; the others read the record that won. A process that dies before the rename leaves
//! only its temporary file (`.<peer>.<pid>.<n>.<nanos>.tmp`), which no record name can match.
//!
//! Writing records is supported on Linux and Android only; elsewhere the writes fail with
//! [`PeerFileError::Unsupported`].

use std::error::Error;
use std::fmt;
use std::fs::File;
use std::io::{self, Read};
use std::path::{Path, PathBuf};

use tasty_net_identity::{DeviceId, PeerKeyStore, PeerRef, PinOutcome, StoreError};

/// The longest peer name, in bytes.
const MAX_PEER_LEN: usize = 64;
/// The file name extension of a record.
const RECORD_EXT: &str = "id";
/// The length of a record: 64 hex digits and a newline.
const RECORD_LEN: usize = 65;

/// A [`PeerKeyStore`] that keeps each peer's record in its own file under one folder.
///
/// The folder is created when the first record is written. Every method can be called from
/// several threads, and from several stores on the same folder, at the same time.
#[derive(Debug, Clone)]
pub(crate) struct FilePeerKeyStore {
    dir: PathBuf,
}

impl FilePeerKeyStore {
    /// A store for the records in `dir`.
    pub(crate) fn new(dir: impl Into<PathBuf>) -> Self {
        Self { dir: dir.into() }
    }

    /// The record path of `peer`, after checking that the name can be a file name.
    fn record_path(&self, peer: &PeerRef) -> Result<PathBuf, PeerFileError> {
        let name = peer.as_str();
        let valid = (1..=MAX_PEER_LEN).contains(&name.len())
            && name
                .bytes()
                .all(|b| b.is_ascii_alphanumeric() || b == b'_' || b == b'-');
        if !valid {
            return Err(PeerFileError::InvalidPeer {
                name: name.to_owned(),
            });
        }
        Ok(self.dir.join(format!("{name}.{RECORD_EXT}")))
    }
}

impl PeerKeyStore for FilePeerKeyStore {
    fn get(&self, peer: &PeerRef) -> Result<Option<DeviceId>, StoreError> {
        let path = self.record_path(peer)?;
        Ok(read_record(&path)?)
    }

    fn pin_if_absent(&self, peer: &PeerRef, id: &DeviceId) -> Result<PinOutcome, StoreError> {
        let path = self.record_path(peer)?;
        // Most calls find the record; only a missing one is worth a temporary file.
        if let Some(existing) = read_record(&path)? {
            return Ok(PinOutcome::Existing(existing));
        }
        Ok(write::pin(&self.dir, peer, &path, id)?)
    }

    fn replace(&self, peer: &PeerRef, id: &DeviceId) -> Result<(), StoreError> {
        let path = self.record_path(peer)?;
        Ok(write::replace(&self.dir, peer, &path, id)?)
    }
}

/// Reads the record at `path`. A missing file is `None`; a file in the wrong format is
/// [`PeerFileError::Corrupt`] and is left as it is.
fn read_record(path: &Path) -> Result<Option<DeviceId>, PeerFileError> {
    let file = match File::open(path) {
        Ok(file) => file,
        Err(e) if e.kind() == io::ErrorKind::NotFound => return Ok(None),
        Err(e) => return Err(PeerFileError::io("open", path, e)),
    };
    let mut bytes = Vec::with_capacity(RECORD_LEN + 1);
    // One byte more than a record, so a longer file is noticed without reading all of it.
    file.take(RECORD_LEN as u64 + 1)
        .read_to_end(&mut bytes)
        .map_err(|e| PeerFileError::io("read", path, e))?;
    let corrupt = |reason: &str| PeerFileError::Corrupt {
        path: path.to_owned(),
        reason: reason.to_owned(),
    };
    let hex = match bytes.split_last() {
        Some((b'\n', hex)) if bytes.len() == RECORD_LEN => hex,
        _ => return Err(corrupt("not 64 hex digits and a newline")),
    };
    let hex = std::str::from_utf8(hex).map_err(|_| corrupt("not text"))?;
    hex.parse::<DeviceId>()
        .map(Some)
        .map_err(|e| corrupt(&e.to_string()))
}

/// Why the file store failed. It travels inside [`StoreError`].
#[derive(Debug)]
pub(crate) enum PeerFileError {
    /// The peer name is not 1 to 64 of `A-Z`, `a-z`, `0-9`, `_` and `-`.
    InvalidPeer { name: String },
    /// The record file does not have the expected format. It was not changed.
    Corrupt { path: PathBuf, reason: String },
    /// A file system call failed.
    Io {
        op: &'static str,
        path: PathBuf,
        source: io::Error,
    },
    /// Records cannot be written safely here: the OS or the file system does not offer the
    /// rename that refuses to replace.
    Unsupported { reason: String },
}

impl PeerFileError {
    fn io(op: &'static str, path: &Path, source: io::Error) -> Self {
        Self::Io {
            op,
            path: path.to_owned(),
            source,
        }
    }
}

impl fmt::Display for PeerFileError {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        match self {
            Self::InvalidPeer { name } => write!(
                f,
                "peer name {name:?} is not 1 to {MAX_PEER_LEN} of A-Z, a-z, 0-9, _ and -"
            ),
            Self::Corrupt { path, reason } => {
                write!(f, "peer record {} is corrupt: {reason}", path.display())
            }
            Self::Io { op, path, source } => {
                write!(f, "cannot {op} {}: {source}", path.display())
            }
            Self::Unsupported { reason } => write!(f, "cannot write peer records: {reason}"),
        }
    }
}

impl Error for PeerFileError {
    fn source(&self) -> Option<&(dyn Error + 'static)> {
        match self {
            Self::Io { source, .. } => Some(source),
            _ => None,
        }
    }
}

impl From<PeerFileError> for StoreError {
    fn from(error: PeerFileError) -> Self {
        StoreError::new(error)
    }
}

#[cfg(any(target_os = "linux", target_os = "android"))]
mod write {
    use std::ffi::CString;
    use std::fs::{DirBuilder, File, OpenOptions};
    use std::io::{self, Write};
    use std::os::unix::ffi::OsStrExt;
    use std::os::unix::fs::{DirBuilderExt, OpenOptionsExt};
    use std::path::{Path, PathBuf};
    use std::sync::atomic::{AtomicU64, Ordering};
    use std::time::{SystemTime, UNIX_EPOCH};

    use tasty_net_identity::{DeviceId, PeerRef, PinOutcome};

    use super::{PeerFileError, read_record};

    /// Publishes the record of `id` at `path` unless one is there.
    pub(super) fn pin(
        dir: &Path,
        peer: &PeerRef,
        path: &Path,
        id: &DeviceId,
    ) -> Result<PinOutcome, PeerFileError> {
        let temp = write_temp(dir, peer, id)?;
        match rename_noreplace(&temp, path) {
            Ok(()) => {
                sync_dir(dir)?;
                Ok(PinOutcome::Inserted)
            }
            Err(e) => {
                remove_temp(&temp);
                if e.raw_os_error() == Some(libc::EEXIST) {
                    // Another call published first; its record is complete once it has a name.
                    match read_record(path)? {
                        Some(existing) => Ok(PinOutcome::Existing(existing)),
                        None => Err(PeerFileError::io(
                            "read the record that won",
                            path,
                            io::ErrorKind::NotFound.into(),
                        )),
                    }
                } else if matches!(e.raw_os_error(), Some(libc::ENOSYS | libc::EINVAL)) {
                    // No hard link fallback: the app data folder refuses links on Android.
                    Err(PeerFileError::Unsupported {
                        reason: format!("renameat2 with RENAME_NOREPLACE is not offered: {e}"),
                    })
                } else {
                    Err(PeerFileError::io("publish", path, e))
                }
            }
        }
    }

    /// Replaces the record at `path` with the record of `id`.
    pub(super) fn replace(
        dir: &Path,
        peer: &PeerRef,
        path: &Path,
        id: &DeviceId,
    ) -> Result<(), PeerFileError> {
        let temp = write_temp(dir, peer, id)?;
        if let Err(e) = std::fs::rename(&temp, path) {
            remove_temp(&temp);
            return Err(PeerFileError::io("replace", path, e));
        }
        sync_dir(dir)
    }

    /// Writes and syncs the record of `id` in a new temporary file in `dir` (created when
    /// missing, private to the app) and returns its path. A failure removes the file.
    fn write_temp(dir: &Path, peer: &PeerRef, id: &DeviceId) -> Result<PathBuf, PeerFileError> {
        DirBuilder::new()
            .recursive(true)
            .mode(0o700)
            .create(dir)
            .map_err(|e| PeerFileError::io("create the folder", dir, e))?;
        let temp = dir.join(temp_name(peer));
        let mut file = OpenOptions::new()
            .write(true)
            .create_new(true)
            .mode(0o600)
            .open(&temp)
            .map_err(|e| PeerFileError::io("create", &temp, e))?;
        let written = file
            .write_all(record_content(id).as_bytes())
            .and_then(|()| file.sync_all());
        drop(file);
        if let Err(e) = written {
            remove_temp(&temp);
            return Err(PeerFileError::io("write", &temp, e));
        }
        Ok(temp)
    }

    /// A temporary file name no other call uses: dot first and `.tmp` last, so it is never a
    /// record name (peer names have no dot).
    fn temp_name(peer: &PeerRef) -> String {
        static COUNTER: AtomicU64 = AtomicU64::new(0);
        let n = COUNTER.fetch_add(1, Ordering::Relaxed);
        let nanos = SystemTime::now()
            .duration_since(UNIX_EPOCH)
            .map(|d| d.subsec_nanos())
            .unwrap_or_default();
        format!(".{}.{}.{n}.{nanos}.tmp", peer.as_str(), std::process::id())
    }

    /// The content of the record for `id`.
    fn record_content(id: &DeviceId) -> String {
        format!("{id}\n")
    }

    /// Removes a temporary file this call made. A file that cannot be removed stays; it is
    /// never read as a record.
    fn remove_temp(temp: &Path) {
        let _ignored = std::fs::remove_file(temp);
    }

    /// Syncs `dir` so a rename in it survives a power loss.
    fn sync_dir(dir: &Path) -> Result<(), PeerFileError> {
        File::open(dir)
            .and_then(|d| d.sync_all())
            .map_err(|e| PeerFileError::io("sync the folder", dir, e))
    }

    /// Renames `from` to `to` unless something is at `to` (`EEXIST`). The `renameat2` syscall
    /// with `RENAME_NOREPLACE`, called directly because bionic has its wrapper only from API
    /// level 30 and the app supports API level 29.
    fn rename_noreplace(from: &Path, to: &Path) -> io::Result<()> {
        let (from, to) = (c_path(from)?, c_path(to)?);
        // SAFETY: both pointers are NUL-terminated strings owned by `from` and `to`, which live
        // until the call returns; the kernel only reads them. The arguments match the kernel
        // signature renameat2(int, const char *, int, const char *, unsigned int).
        let result = unsafe {
            libc::syscall(
                libc::SYS_renameat2,
                libc::AT_FDCWD,
                from.as_ptr(),
                libc::AT_FDCWD,
                to.as_ptr(),
                libc::RENAME_NOREPLACE,
            )
        };
        if result.is_negative() {
            Err(io::Error::last_os_error())
        } else {
            Ok(())
        }
    }

    fn c_path(path: &Path) -> io::Result<CString> {
        CString::new(path.as_os_str().as_bytes())
            .map_err(|_| io::Error::new(io::ErrorKind::InvalidInput, "path contains a NUL byte"))
    }
}

#[cfg(not(any(target_os = "linux", target_os = "android")))]
mod write {
    use std::path::Path;

    use tasty_net_identity::{DeviceId, PeerRef, PinOutcome};

    use super::PeerFileError;

    fn unsupported() -> PeerFileError {
        PeerFileError::Unsupported {
            reason: "unsupported: records are written on Linux and Android only".to_owned(),
        }
    }

    pub(super) fn pin(
        _dir: &Path,
        _peer: &PeerRef,
        _path: &Path,
        _id: &DeviceId,
    ) -> Result<PinOutcome, PeerFileError> {
        Err(unsupported())
    }

    pub(super) fn replace(
        _dir: &Path,
        _peer: &PeerRef,
        _path: &Path,
        _id: &DeviceId,
    ) -> Result<(), PeerFileError> {
        Err(unsupported())
    }
}

#[cfg(test)]
mod tests {
    use std::sync::{Arc, Barrier};
    use std::thread;
    use std::time::{SystemTime, UNIX_EPOCH};

    use tasty_net_identity::{DeviceKey, check_peer, reregister};

    use super::*;

    /// A fresh test folder, removed with everything in it on drop. Its `peers` folder does not
    /// exist yet, so the first write also creates the store folder.
    struct TempDir(PathBuf);

    impl TempDir {
        fn new(name: &str) -> Self {
            let nanos = SystemTime::now()
                .duration_since(UNIX_EPOCH)
                .map(|d| d.as_nanos())
                .unwrap_or_default();
            let parent = std::env::temp_dir().join(format!(
                "tasty-mobile-core-peers-{name}-{}-{nanos}",
                std::process::id()
            ));
            std::fs::create_dir(&parent).expect("create the test folder");
            Self(parent)
        }

        fn store_dir(&self) -> PathBuf {
            self.0.join("peers")
        }

        fn store(&self) -> FilePeerKeyStore {
            FilePeerKeyStore::new(self.store_dir())
        }
    }

    impl Drop for TempDir {
        fn drop(&mut self) {
            let _ignored = std::fs::remove_dir_all(&self.0);
        }
    }

    fn id(seed: u8) -> DeviceId {
        DeviceKey::from_seed([seed; 32]).id()
    }

    /// The record file content for `id`, written the way the store writes it.
    fn record_content(id: &DeviceId) -> String {
        format!("{id}\n")
    }

    fn peer(name: &str) -> PeerRef {
        PeerRef::new(name)
    }

    fn file_error(error: StoreError) -> PeerFileError {
        *error
            .into_inner()
            .downcast::<PeerFileError>()
            .expect("a file store error")
    }

    /// Writes `content` as the record of `name`, creating the store folder.
    fn write_raw(dir: &TempDir, name: &str, content: &[u8]) -> PathBuf {
        std::fs::create_dir_all(dir.store_dir()).expect("create the store folder");
        let path = dir.store_dir().join(format!("{name}.id"));
        std::fs::write(&path, content).expect("write the record");
        path
    }

    #[test]
    fn an_unknown_peer_has_no_record() {
        let dir = TempDir::new("unknown");
        assert_eq!(dir.store().get(&peer("p1")).expect("get"), None);
    }

    #[test]
    fn invalid_peer_names_are_refused() {
        let dir = TempDir::new("names");
        let store = dir.store();
        let long = "a".repeat(MAX_PEER_LEN + 1);
        for name in [
            "",
            ".",
            "..",
            "a.b",
            "a/b",
            "a\\b",
            " a",
            "한",
            long.as_str(),
        ] {
            let error = file_error(store.get(&peer(name)).expect_err(name));
            assert!(
                matches!(error, PeerFileError::InvalidPeer { .. }),
                "{name:?}: {error}"
            );
            let error = file_error(store.pin_if_absent(&peer(name), &id(1)).expect_err(name));
            assert!(
                matches!(error, PeerFileError::InvalidPeer { .. }),
                "{name:?}: {error}"
            );
            let error = file_error(store.replace(&peer(name), &id(1)).expect_err(name));
            assert!(
                matches!(error, PeerFileError::InvalidPeer { .. }),
                "{name:?}: {error}"
            );
        }
        let longest = "Z9_-".repeat(MAX_PEER_LEN / 4);
        assert_eq!(store.get(&peer(&longest)).expect("longest name"), None);
        assert!(!dir.store_dir().exists());
    }

    #[test]
    fn a_corrupt_record_is_reported_and_kept() {
        let dir = TempDir::new("corrupt");
        let valid = record_content(&id(1));
        let mut not_text = vec![0xff; 64];
        not_text.push(b'\n');
        let cases: Vec<Vec<u8>> = vec![
            b"not a device id\n".to_vec(),
            valid.as_bytes()[..64].to_vec(),
            valid.to_uppercase().into_bytes(),
            format!("{valid}\n").into_bytes(),
            not_text,
            Vec::new(),
        ];
        for (i, content) in cases.iter().enumerate() {
            let name = format!("p{i}");
            let path = write_raw(&dir, &name, content);
            let error = file_error(dir.store().get(&peer(&name)).expect_err(&name));
            assert!(
                matches!(error, PeerFileError::Corrupt { .. }),
                "{i}: {error}"
            );
            assert_eq!(&std::fs::read(&path).expect("read"), content, "{i}");
        }
    }

    #[test]
    fn a_record_written_elsewhere_is_read() {
        let dir = TempDir::new("read");
        write_raw(&dir, "p1", record_content(&id(7)).as_bytes());
        assert_eq!(dir.store().get(&peer("p1")).expect("get"), Some(id(7)));
    }

    #[cfg_attr(
        any(target_os = "linux", target_os = "android"),
        ignore = "the write path"
    )]
    #[test]
    fn writes_are_unsupported_off_linux() {
        let dir = TempDir::new("unsupported");
        let store = dir.store();
        let error = file_error(store.pin_if_absent(&peer("p1"), &id(1)).expect_err("pin"));
        assert!(
            matches!(error, PeerFileError::Unsupported { .. }),
            "{error}"
        );
        let error = file_error(store.replace(&peer("p1"), &id(1)).expect_err("replace"));
        assert!(
            matches!(error, PeerFileError::Unsupported { .. }),
            "{error}"
        );
        assert!(!dir.store_dir().exists());
    }

    #[cfg_attr(
        not(any(target_os = "linux", target_os = "android")),
        ignore = "writes records with renameat2, Linux and Android only"
    )]
    #[test]
    fn the_first_pin_inserts_and_the_next_finds_it() {
        let dir = TempDir::new("pin");
        let store = dir.store();
        assert_eq!(
            store.pin_if_absent(&peer("p1"), &id(1)).expect("first"),
            PinOutcome::Inserted
        );
        assert_eq!(
            store.pin_if_absent(&peer("p1"), &id(1)).expect("again"),
            PinOutcome::Existing(id(1))
        );
        assert_eq!(store.get(&peer("p1")).expect("get"), Some(id(1)));
        let path = dir.store_dir().join("p1.id");
        assert_eq!(
            std::fs::read_to_string(&path).expect("read"),
            record_content(&id(1))
        );
        // Another store on the same folder sees the same record.
        assert_eq!(
            dir.store().get(&peer("p1")).expect("other store"),
            Some(id(1))
        );
        // The folders made for the record are private, and no temporary file is left.
        #[cfg(unix)]
        {
            use std::os::unix::fs::PermissionsExt;
            let mode = |p: &Path| std::fs::metadata(p).expect("metadata").permissions().mode();
            assert_eq!(mode(&dir.store_dir()) & 0o777, 0o700);
            assert_eq!(mode(&path) & 0o777, 0o600);
        }
        assert_eq!(entries(&dir), vec!["p1.id".to_owned()]);
    }

    #[cfg_attr(
        not(any(target_os = "linux", target_os = "android")),
        ignore = "writes records with renameat2, Linux and Android only"
    )]
    #[test]
    fn a_pin_with_another_id_keeps_the_record() {
        let dir = TempDir::new("keep");
        let store = dir.store();
        store.pin_if_absent(&peer("p1"), &id(1)).expect("first");
        let path = dir.store_dir().join("p1.id");
        let before = std::fs::read(&path).expect("read");
        assert_eq!(
            store.pin_if_absent(&peer("p1"), &id(2)).expect("other id"),
            PinOutcome::Existing(id(1))
        );
        assert_eq!(std::fs::read(&path).expect("read"), before);
        let verdict = check_peer(&store, &peer("p1"), &id(2)).expect("check");
        assert!(!verdict.is_accepted(), "{verdict:?}");
        assert_eq!(store.get(&peer("p1")).expect("get"), Some(id(1)));
        // Peers are independent.
        assert_eq!(
            store.pin_if_absent(&peer("p2"), &id(2)).expect("p2"),
            PinOutcome::Inserted
        );
    }

    #[cfg_attr(
        not(any(target_os = "linux", target_os = "android")),
        ignore = "writes records with renameat2, Linux and Android only"
    )]
    #[test]
    fn reregister_replaces_the_record() {
        let dir = TempDir::new("reregister");
        let store = dir.store();
        store.pin_if_absent(&peer("p1"), &id(1)).expect("first");
        reregister(&store, &peer("p1"), &id(2)).expect("reregister");
        assert_eq!(store.get(&peer("p1")).expect("get"), Some(id(2)));
        assert_eq!(
            store.pin_if_absent(&peer("p1"), &id(1)).expect("old id"),
            PinOutcome::Existing(id(2))
        );
        // A peer without a record can be registered directly.
        reregister(&store, &peer("p2"), &id(3)).expect("new peer");
        assert_eq!(store.get(&peer("p2")).expect("get"), Some(id(3)));
        assert_eq!(entries(&dir), vec!["p1.id".to_owned(), "p2.id".to_owned()]);
    }

    #[cfg_attr(
        not(any(target_os = "linux", target_os = "android")),
        ignore = "writes records with renameat2, Linux and Android only"
    )]
    #[test]
    fn a_pin_keeps_a_corrupt_record() {
        let dir = TempDir::new("pin-corrupt");
        let path = write_raw(&dir, "p1", b"broken\n");
        let error = file_error(
            dir.store()
                .pin_if_absent(&peer("p1"), &id(1))
                .expect_err("corrupt"),
        );
        assert!(matches!(error, PeerFileError::Corrupt { .. }), "{error}");
        assert_eq!(std::fs::read(&path).expect("read"), b"broken\n");
        assert_eq!(entries(&dir), vec!["p1.id".to_owned()]);
    }

    #[cfg_attr(
        not(any(target_os = "linux", target_os = "android")),
        ignore = "writes records with renameat2, Linux and Android only"
    )]
    #[test]
    fn a_left_temporary_file_is_ignored() {
        let dir = TempDir::new("leftover");
        // What a process that died before its rename leaves behind.
        std::fs::create_dir_all(dir.store_dir()).expect("create the store folder");
        let left = dir.store_dir().join(".p1.1.0.0.tmp");
        std::fs::write(&left, record_content(&id(9))).expect("write");
        let store = dir.store();
        assert_eq!(store.get(&peer("p1")).expect("get"), None);
        assert_eq!(
            store.pin_if_absent(&peer("p1"), &id(1)).expect("pin"),
            PinOutcome::Inserted
        );
        assert_eq!(store.get(&peer("p1")).expect("get"), Some(id(1)));
        assert!(left.exists());
    }

    #[cfg_attr(
        not(any(target_os = "linux", target_os = "android")),
        ignore = "writes records with renameat2, Linux and Android only"
    )]
    #[test]
    fn threads_pinning_at_once_insert_once() {
        const THREADS: usize = 8;
        let dir = TempDir::new("race");
        let barrier = Arc::new(Barrier::new(THREADS));
        let handles: Vec<_> = (0..THREADS)
            .map(|i| {
                let (barrier, store) = (Arc::clone(&barrier), dir.store());
                thread::spawn(move || {
                    let mine = id(u8::try_from(i).expect("small") + 1);
                    barrier.wait();
                    (mine, store.pin_if_absent(&peer("p1"), &mine))
                })
            })
            .collect();
        let results: Vec<_> = handles
            .into_iter()
            .map(|h| {
                let (mine, outcome) = h.join().expect("thread");
                (mine, outcome.expect("pin"))
            })
            .collect();
        let winners: Vec<_> = results
            .iter()
            .filter(|(_, outcome)| *outcome == PinOutcome::Inserted)
            .map(|(mine, _)| *mine)
            .collect();
        assert_eq!(winners.len(), 1, "{results:?}");
        let winner = winners[0];
        // Every other call got the winner's ID, not its own.
        assert_eq!(
            results
                .iter()
                .filter(|(_, o)| *o == PinOutcome::Existing(winner))
                .count(),
            THREADS - 1,
            "{results:?}"
        );
        assert_eq!(dir.store().get(&peer("p1")).expect("get"), Some(winner));
        assert_eq!(entries(&dir), vec!["p1.id".to_owned()]);
    }

    /// The sorted names in the store folder.
    fn entries(dir: &TempDir) -> Vec<String> {
        let mut names: Vec<_> = std::fs::read_dir(dir.store_dir())
            .expect("read the store folder")
            .map(|e| e.expect("entry").file_name().to_string_lossy().into_owned())
            .collect();
        names.sort();
        names
    }
}
