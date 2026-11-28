//! Reads the `workspace.overview` reply of a remote Tasty.
//!
//! The wire types come from `tasty-attach-protocol`. Only app records cross the boundary, so
//! Kotlin needs no JSON library and never sees a tasty type. Fields the app does not know are
//! ignored, so a newer remote that adds fields still parses.

use tasty_attach_protocol::{OverviewSurface, OverviewWorkspace, WorkspaceOverview};

/// The workspaces and server capabilities of a remote Tasty, read in one state read.
#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct RemoteWorkspaceOverview {
    pub workspaces: Vec<RemoteWorkspace>,
    /// Capability names of the remote server.
    pub capabilities: Vec<String>,
}

/// One workspace of the remote Tasty.
#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct RemoteWorkspace {
    /// The workspace ID on the remote server.
    pub id: u32,
    pub name: String,
    pub subtitle: String,
    /// How many surfaces are busy.
    pub busy_count: u64,
    /// Whether the workspace mirrors another server.
    pub mirror: bool,
    /// The attach client that holds the workspace, if any.
    pub ws_holder: Option<u32>,
    pub surfaces: Vec<RemoteSurface>,
}

/// One surface of a remote workspace.
#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct RemoteSurface {
    /// The surface ID on the remote server.
    pub id: u32,
    /// `terminal` or the kind a plugin registered.
    pub kind: String,
    pub title: String,
    pub busy: bool,
    /// The attach client that holds the surface, if any.
    pub holder: Option<u32>,
    /// The grant order of that hold, if any.
    pub granted_seq: Option<u64>,
}

/// Why a `workspace.overview` reply could not be read.
#[derive(Debug, PartialEq, Eq, uniffi::Error)]
pub enum OverviewError {
    /// The reply is not JSON of the expected shape. `reason` is the JSON error.
    Malformed { reason: String },
}

impl std::fmt::Display for OverviewError {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        match self {
            Self::Malformed { reason } => write!(f, "malformed workspace overview: {reason}"),
        }
    }
}

impl std::error::Error for OverviewError {}

/// Reads the `result` of a `workspace.overview` reply.
#[uniffi::export]
pub fn parse_workspace_overview(json: String) -> Result<RemoteWorkspaceOverview, OverviewError> {
    let overview: WorkspaceOverview =
        serde_json::from_str(&json).map_err(|e| OverviewError::Malformed {
            reason: e.to_string(),
        })?;
    Ok(RemoteWorkspaceOverview {
        workspaces: overview.workspaces.into_iter().map(workspace).collect(),
        capabilities: overview.capabilities,
    })
}

fn workspace(ws: OverviewWorkspace) -> RemoteWorkspace {
    RemoteWorkspace {
        id: ws.id,
        name: ws.name,
        subtitle: ws.subtitle,
        // usize is at most 64 bits on every target the app builds for.
        busy_count: u64::try_from(ws.busy_count).unwrap_or(u64::MAX),
        mirror: ws.mirror,
        ws_holder: ws.ws_holder,
        surfaces: ws.surfaces.into_iter().map(surface).collect(),
    }
}

fn surface(s: OverviewSurface) -> RemoteSurface {
    RemoteSurface {
        id: s.id,
        kind: s.kind,
        title: s.title,
        busy: s.busy,
        holder: s.holder,
        granted_seq: s.granted_seq,
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn parse(json: &str) -> Result<RemoteWorkspaceOverview, OverviewError> {
        parse_workspace_overview(json.to_owned())
    }

    fn is_malformed(json: &str) -> bool {
        matches!(parse(json), Err(OverviewError::Malformed { .. }))
    }

    #[test]
    fn reads_the_reply_and_leaves_missing_holders_empty() {
        let json = r#"{
            "workspaces": [
                {"id": 1, "name": "main", "subtitle": "~/src", "busy_count": 1, "mirror": false,
                 "surfaces": [
                    {"id": 10, "kind": "terminal", "title": "zsh", "busy": true},
                    {"id": 11, "kind": "explorer", "title": "files", "busy": false,
                     "holder": 7, "granted_seq": 42}
                 ]},
                {"id": 2, "name": "remote", "subtitle": "", "busy_count": 0, "mirror": true,
                 "ws_holder": 3, "surfaces": []}
            ],
            "capabilities": ["workspace.overview", "attach"]
        }"#;
        let expected = RemoteWorkspaceOverview {
            workspaces: vec![
                RemoteWorkspace {
                    id: 1,
                    name: "main".into(),
                    subtitle: "~/src".into(),
                    busy_count: 1,
                    mirror: false,
                    ws_holder: None,
                    surfaces: vec![
                        RemoteSurface {
                            id: 10,
                            kind: "terminal".into(),
                            title: "zsh".into(),
                            busy: true,
                            holder: None,
                            granted_seq: None,
                        },
                        RemoteSurface {
                            id: 11,
                            kind: "explorer".into(),
                            title: "files".into(),
                            busy: false,
                            holder: Some(7),
                            granted_seq: Some(42),
                        },
                    ],
                },
                RemoteWorkspace {
                    id: 2,
                    name: "remote".into(),
                    subtitle: String::new(),
                    busy_count: 0,
                    mirror: true,
                    ws_holder: Some(3),
                    surfaces: vec![],
                },
            ],
            capabilities: vec!["workspace.overview".into(), "attach".into()],
        };
        assert_eq!(parse(json), Ok(expected));
    }

    #[test]
    fn reads_an_empty_server() {
        let overview = parse(r#"{"workspaces": [], "capabilities": []}"#).unwrap();
        assert!(overview.workspaces.is_empty());
        assert!(overview.capabilities.is_empty());
    }

    #[test]
    fn ignores_fields_it_does_not_know() {
        let json = r#"{
            "version": 2,
            "workspaces": [
                {"id": 1, "name": "a", "subtitle": "", "busy_count": 0, "mirror": false,
                 "color": "red", "surfaces": [
                    {"id": 5, "kind": "terminal", "title": "t", "busy": false, "cwd": "/tmp"}
                 ]}
            ],
            "capabilities": ["x"]
        }"#;
        let overview = parse(json).unwrap();
        assert_eq!(overview.workspaces[0].surfaces[0].id, 5);
        assert_eq!(overview.capabilities, vec!["x".to_owned()]);
    }

    #[test]
    fn rejects_a_reply_of_the_wrong_shape() {
        // Not JSON.
        assert!(is_malformed("workspaces"));
        // A missing field.
        assert!(is_malformed(r#"{"workspaces": []}"#));
        assert!(is_malformed(
            r#"{"workspaces": [{"id": 1, "name": "a", "subtitle": "", "busy_count": 0,
                "mirror": false}], "capabilities": []}"#
        ));
        // Wrong types.
        assert!(is_malformed(r#"{"workspaces": {}, "capabilities": []}"#));
        assert!(is_malformed(
            r#"{"workspaces": [{"id": "1", "name": "a", "subtitle": "", "busy_count": 0,
                "mirror": false, "surfaces": []}], "capabilities": []}"#
        ));
        assert!(is_malformed(
            r#"{"workspaces": [{"id": -1, "name": "a", "subtitle": "", "busy_count": 0,
                "mirror": false, "surfaces": []}], "capabilities": []}"#
        ));
        assert!(is_malformed(
            r#"{"workspaces": [{"id": 1, "name": "a", "subtitle": "", "busy_count": 0,
                "mirror": false, "surfaces": [{"id": 2, "kind": "terminal", "title": "t",
                "busy": "yes"}]}], "capabilities": []}"#
        ));
    }

    #[test]
    fn keeps_a_large_busy_count() {
        let reply = |count: &str| {
            format!(
                r#"{{"workspaces": [{{"id": 1, "name": "a", "subtitle": "", "busy_count": {count},
                    "mirror": false, "surfaces": []}}], "capabilities": []}}"#
            )
        };
        let overview = parse(&reply("18446744073709551615")).unwrap();
        assert_eq!(overview.workspaces[0].busy_count, u64::MAX);
        let overview = parse(&reply("4294967296")).unwrap();
        assert_eq!(overview.workspaces[0].busy_count, 1 << 32);
        assert!(is_malformed(&reply("18446744073709551616")));
    }

    /// The reply the remote serializes from the tasty type reads back to the same values.
    #[test]
    fn round_trips_the_tasty_type() {
        let sent = WorkspaceOverview {
            workspaces: vec![
                OverviewWorkspace {
                    id: 4,
                    name: "w".into(),
                    subtitle: "s".into(),
                    busy_count: 2,
                    mirror: false,
                    ws_holder: Some(9),
                    surfaces: vec![
                        OverviewSurface {
                            id: 40,
                            kind: "terminal".into(),
                            title: "t".into(),
                            busy: true,
                            holder: Some(9),
                            granted_seq: Some(u64::MAX),
                        },
                        OverviewSurface {
                            id: 41,
                            kind: "plugin.kind".into(),
                            title: "p".into(),
                            busy: false,
                            holder: None,
                            granted_seq: None,
                        },
                    ],
                },
                OverviewWorkspace {
                    id: u32::MAX,
                    name: String::new(),
                    subtitle: String::new(),
                    busy_count: 0,
                    mirror: true,
                    ws_holder: None,
                    surfaces: vec![],
                },
            ],
            capabilities: vec!["a".into(), "b".into()],
        };
        let json = serde_json::to_string(&sent).unwrap();
        let read = parse(&json).unwrap();
        let back = WorkspaceOverview {
            workspaces: read
                .workspaces
                .into_iter()
                .map(|w| OverviewWorkspace {
                    id: w.id,
                    name: w.name,
                    subtitle: w.subtitle,
                    busy_count: usize::try_from(w.busy_count).unwrap(),
                    mirror: w.mirror,
                    ws_holder: w.ws_holder,
                    surfaces: w
                        .surfaces
                        .into_iter()
                        .map(|s| OverviewSurface {
                            id: s.id,
                            kind: s.kind,
                            title: s.title,
                            busy: s.busy,
                            holder: s.holder,
                            granted_seq: s.granted_seq,
                        })
                        .collect(),
                })
                .collect(),
            capabilities: read.capabilities,
        };
        assert_eq!(back, sent);
    }
}
