//! Rust core of the Tasty Android app.
//!
//! Wraps the desktop `tasty-terminal` crate (PTY + VTE) so Kotlin can run a local shell
//! and draw its screen. Kotlin owns the theme, so colors cross the boundary unresolved.

mod snapshot;
mod workspace;

use std::path::Path;
use std::sync::{Arc, Mutex, MutexGuard};

use tasty_terminal::{Pty, Terminal, TerminalConfig, spawn_terminal};

pub use snapshot::{CellColor, ScreenSnapshot, TextSpan};
pub use workspace::{
    LayoutNode, SplitOrientation, StoreSnapshot, TabInfo, WorkspaceLayout, WorkspaceStore,
    WorkspaceSummary,
};

uniffi::setup_scaffolding!();

/// Called from the PTY reader thread whenever new output arrived or the child exited.
/// Implementations must be cheap and must not call back into the session synchronously.
#[uniffi::export(with_foreign)]
pub trait OutputListener: Send + Sync {
    fn on_output(&self);
}

/// An environment variable passed to the shell.
#[derive(uniffi::Record)]
pub struct EnvVar {
    pub key: String,
    pub value: String,
}

/// Everything needed to start a shell.
#[derive(uniffi::Record)]
pub struct ShellConfig {
    pub shell: String,
    pub args: Vec<String>,
    pub working_dir: String,
    pub env: Vec<EnvVar>,
    pub cols: u32,
    pub rows: u32,
}

#[derive(Debug, uniffi::Error)]
pub enum TerminalError {
    SpawnFailed { reason: String },
}

impl std::fmt::Display for TerminalError {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        match self {
            Self::SpawnFailed { reason } => write!(f, "failed to start the shell: {reason}"),
        }
    }
}

impl std::error::Error for TerminalError {}

struct Inner {
    terminal: Terminal,
    pty: Pty,
    exited: bool,
}

/// One shell running in a pseudo terminal.
#[derive(uniffi::Object)]
pub struct TerminalSession {
    inner: Mutex<Inner>,
}

#[uniffi::export]
impl TerminalSession {
    /// Starts the shell. `listener` is called on a background thread when output arrives.
    #[uniffi::constructor]
    pub fn start(
        config: ShellConfig,
        listener: Arc<dyn OutputListener>,
    ) -> Result<Arc<Self>, TerminalError> {
        let args: Vec<&str> = config.args.iter().map(String::as_str).collect();
        let env: Vec<(&str, &str)> = config
            .env
            .iter()
            .map(|v| (v.key.as_str(), v.value.as_str()))
            .collect();
        let terminal_config = TerminalConfig {
            cols: clamp_dimension(config.cols),
            rows: clamp_dimension(config.rows),
            shell: Some(&config.shell),
            args: &args,
            surface_id: 0,
            working_dir: Some(Path::new(&config.working_dir)),
            initial_input: None,
            extra_env: &env,
        };
        let waker = Arc::new(move || listener.on_output());
        let (terminal, pty) =
            spawn_terminal(terminal_config, waker).map_err(|e| TerminalError::SpawnFailed {
                reason: e.to_string(),
            })?;
        Ok(Arc::new(Self {
            inner: Mutex::new(Inner {
                terminal,
                pty,
                exited: false,
            }),
        }))
    }

    /// Sends raw input bytes (typed text or encoded keys) to the shell.
    pub fn write(&self, data: Vec<u8>) {
        let mut inner = self.lock();
        if !inner.exited {
            inner.terminal.send_bytes(&data);
        }
    }

    /// Resizes the grid and tells the shell about the new size.
    pub fn resize(&self, cols: u32, rows: u32) {
        let (cols, rows) = (clamp_dimension(cols), clamp_dimension(rows));
        let mut inner = self.lock();
        if inner.terminal.resize(cols, rows) && !inner.exited {
            // A failed OS resize leaves the shell with the old size; the grid stays correct.
            let _ignored = inner.pty.apply_os_resize(cols, rows);
        }
    }

    /// Reads the visible screen. Also notices when the shell has exited.
    pub fn snapshot(&self) -> ScreenSnapshot {
        let mut inner = self.lock();
        if !inner.exited && inner.pty.observe_exit() {
            inner.exited = true;
        }
        let inner = &*inner;
        snapshot::capture(&inner.terminal, inner.exited)
    }

    /// Whether arrow keys must be sent in application mode (`ESC O A` instead of `ESC [ A`).
    pub fn application_cursor_keys(&self) -> bool {
        self.lock().terminal.application_cursor_keys()
    }

    /// Whether pasted text must be wrapped in bracketed-paste markers.
    pub fn bracketed_paste(&self) -> bool {
        self.lock().terminal.bracketed_paste()
    }

    /// The window title set by the shell (OSC 0/2), if any.
    pub fn title(&self) -> Option<String> {
        self.lock().terminal.current_title()
    }
}

impl TerminalSession {
    fn lock(&self) -> MutexGuard<'_, Inner> {
        // A panic while holding the lock leaves the terminal usable; recover the guard.
        self.inner.lock().unwrap_or_else(|e| e.into_inner())
    }
}

fn clamp_dimension(value: u32) -> usize {
    value.clamp(1, 1000) as usize
}
