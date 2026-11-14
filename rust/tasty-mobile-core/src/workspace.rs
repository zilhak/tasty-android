//! Workspaces, split panes and tabs, following the desktop hierarchy
//! (Workspace > pane tree > Pane > Tab > Surface) in the reduced form the app needs.
//!
//! The store only tracks structure. Kotlin owns the terminal sessions and keys them by
//! surface id; after each change it compares [`StoreSnapshot::surfaces`] with its sessions.

use std::sync::{Mutex, MutexGuard};

const MIN_RATIO: f32 = 0.1;
const MAX_RATIO: f32 = 0.9;

/// How a split arranges its two children.
#[derive(Debug, Clone, Copy, PartialEq, Eq, uniffi::Enum)]
pub enum SplitOrientation {
    /// First child on the left, second on the right.
    SideBySide,
    /// First child on top, second below.
    Stacked,
}

#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct TabInfo {
    pub id: u64,
    pub surface_id: u64,
    pub title: String,
}

/// One node of a workspace's pane tree. Split children refer to other nodes by id.
#[derive(Debug, Clone, PartialEq, uniffi::Enum)]
pub enum LayoutNode {
    Pane {
        id: u64,
        tabs: Vec<TabInfo>,
        active_tab: u64,
    },
    Split {
        id: u64,
        orientation: SplitOrientation,
        /// Share of the space given to `first`, between 0.1 and 0.9.
        ratio: f32,
        first: u64,
        second: u64,
    },
}

#[derive(Debug, Clone, PartialEq, uniffi::Record)]
pub struct WorkspaceLayout {
    pub workspace_id: u64,
    pub root: u64,
    pub nodes: Vec<LayoutNode>,
    pub focused_pane: u64,
}

#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct WorkspaceSummary {
    pub id: u64,
    pub name: String,
}

#[derive(Debug, Clone, PartialEq, uniffi::Record)]
pub struct StoreSnapshot {
    pub workspaces: Vec<WorkspaceSummary>,
    pub active_workspace: u64,
    /// Layout of the active workspace.
    pub layout: WorkspaceLayout,
    /// Every surface in every workspace. Sessions for other ids can be closed.
    pub surfaces: Vec<u64>,
}

#[derive(Debug, Clone)]
struct Tab {
    id: u64,
    surface_id: u64,
    title: String,
}

#[derive(Debug, Clone)]
struct Pane {
    id: u64,
    tabs: Vec<Tab>,
    active_tab: u64,
}

#[derive(Debug, Clone)]
enum Node {
    Pane(Pane),
    Split {
        id: u64,
        orientation: SplitOrientation,
        ratio: f32,
        first: Box<Node>,
        second: Box<Node>,
    },
}

#[derive(Debug, Clone)]
struct Workspace {
    id: u64,
    name: String,
    root: Node,
    focused_pane: u64,
}

struct State {
    workspaces: Vec<Workspace>,
    active: u64,
    next_id: u64,
    created_workspaces: u64,
}

/// The app's workspaces. Starts with one workspace holding one terminal tab.
#[derive(uniffi::Object)]
pub struct WorkspaceStore {
    state: Mutex<State>,
}

impl Default for WorkspaceStore {
    fn default() -> Self {
        Self::create()
    }
}

#[uniffi::export]
impl WorkspaceStore {
    #[uniffi::constructor]
    pub fn new() -> std::sync::Arc<Self> {
        std::sync::Arc::new(Self::create())
    }

    pub fn snapshot(&self) -> StoreSnapshot {
        self.lock().snapshot()
    }

    /// Adds a workspace with one tab, makes it active and returns its id.
    pub fn new_workspace(&self) -> u64 {
        self.lock().add_workspace()
    }

    pub fn select_workspace(&self, workspace_id: u64) -> bool {
        let mut state = self.lock();
        let exists = state.workspaces.iter().any(|w| w.id == workspace_id);
        if exists {
            state.active = workspace_id;
        }
        exists
    }

    /// Closes a workspace. Closing the last one leaves a fresh workspace in its place.
    pub fn close_workspace(&self, workspace_id: u64) -> bool {
        self.lock().close_workspace(workspace_id)
    }

    /// Adds a tab to a pane of the active workspace, selects it and returns its surface id.
    pub fn new_tab(&self, pane_id: u64) -> Option<u64> {
        let mut state = self.lock();
        let (tab_id, surface_id) = (state.take_id(), state.take_id());
        let workspace = state.active_workspace_mut();
        let pane = find_pane(&mut workspace.root, pane_id)?;
        pane.tabs.push(Tab {
            id: tab_id,
            surface_id,
            title: default_tab_title(),
        });
        pane.active_tab = tab_id;
        workspace.focused_pane = pane_id;
        Some(surface_id)
    }

    /// Selects a tab in the active workspace and focuses its pane.
    pub fn select_tab(&self, tab_id: u64) -> bool {
        let mut state = self.lock();
        let workspace = state.active_workspace_mut();
        let Some(pane) = find_pane_with_tab(&mut workspace.root, tab_id) else {
            return false;
        };
        pane.active_tab = tab_id;
        workspace.focused_pane = pane.id;
        true
    }

    /// Closes a tab in the active workspace. An emptied pane closes, and an emptied
    /// workspace closes like [`WorkspaceStore::close_workspace`].
    pub fn close_tab(&self, tab_id: u64) -> bool {
        self.lock().close_tab(tab_id)
    }

    /// Splits a pane of the active workspace. The new pane gets one tab and the focus.
    /// Returns the new pane's id.
    pub fn split_pane(&self, pane_id: u64, orientation: SplitOrientation) -> Option<u64> {
        self.lock().split_pane(pane_id, orientation)
    }

    /// Closes a pane of the active workspace with all its tabs.
    pub fn close_pane(&self, pane_id: u64) -> bool {
        self.lock().close_pane(pane_id)
    }

    pub fn focus_pane(&self, pane_id: u64) -> bool {
        let mut state = self.lock();
        let workspace = state.active_workspace_mut();
        let found = find_pane(&mut workspace.root, pane_id).is_some();
        if found {
            workspace.focused_pane = pane_id;
        }
        found
    }

    /// Moves a split's divider. The ratio is clamped to 0.1..=0.9.
    pub fn set_split_ratio(&self, split_id: u64, ratio: f32) -> bool {
        let mut state = self.lock();
        let workspace = state.active_workspace_mut();
        set_ratio(
            &mut workspace.root,
            split_id,
            ratio.clamp(MIN_RATIO, MAX_RATIO),
        )
    }
}

impl WorkspaceStore {
    fn create() -> Self {
        let mut state = State {
            workspaces: Vec::new(),
            active: 0,
            next_id: 1,
            created_workspaces: 0,
        };
        state.add_workspace();
        Self {
            state: Mutex::new(state),
        }
    }

    fn lock(&self) -> MutexGuard<'_, State> {
        self.state.lock().unwrap_or_else(|e| e.into_inner())
    }
}

impl State {
    fn take_id(&mut self) -> u64 {
        let id = self.next_id;
        self.next_id += 1;
        id
    }

    fn new_pane(&mut self) -> Pane {
        let (pane_id, tab_id, surface_id) = (self.take_id(), self.take_id(), self.take_id());
        Pane {
            id: pane_id,
            tabs: vec![Tab {
                id: tab_id,
                surface_id,
                title: default_tab_title(),
            }],
            active_tab: tab_id,
        }
    }

    fn add_workspace(&mut self) -> u64 {
        let id = self.take_id();
        let pane = self.new_pane();
        self.created_workspaces += 1;
        self.workspaces.push(Workspace {
            id,
            name: format!("Workspace {}", self.created_workspaces),
            focused_pane: pane.id,
            root: Node::Pane(pane),
        });
        self.active = id;
        id
    }

    fn active_workspace_mut(&mut self) -> &mut Workspace {
        let active = self.active;
        let index = self
            .workspaces
            .iter()
            .position(|w| w.id == active)
            .unwrap_or(0);
        &mut self.workspaces[index]
    }

    fn close_workspace(&mut self, workspace_id: u64) -> bool {
        let Some(index) = self.workspaces.iter().position(|w| w.id == workspace_id) else {
            return false;
        };
        self.workspaces.remove(index);
        if self.workspaces.is_empty() {
            self.add_workspace();
        } else if self.active == workspace_id {
            let next = index.min(self.workspaces.len() - 1);
            self.active = self.workspaces[next].id;
        }
        true
    }

    fn close_tab(&mut self, tab_id: u64) -> bool {
        let workspace = self.active_workspace_mut();
        let Some(pane) = find_pane_with_tab(&mut workspace.root, tab_id) else {
            return false;
        };
        let index = pane.tabs.iter().position(|t| t.id == tab_id).unwrap_or(0);
        pane.tabs.remove(index);
        if let Some(next) = pane.tabs.get(index.min(pane.tabs.len().saturating_sub(1))) {
            if pane.active_tab == tab_id {
                pane.active_tab = next.id;
            }
            return true;
        }
        let pane_id = pane.id;
        self.close_pane(pane_id)
    }

    fn close_pane(&mut self, pane_id: u64) -> bool {
        let workspace = self.active_workspace_mut();
        match remove_pane(workspace.root.clone(), pane_id) {
            Removal::NotFound => false,
            Removal::Emptied => {
                let id = workspace.id;
                self.close_workspace(id)
            }
            Removal::Kept(root) => {
                workspace.root = root;
                if workspace.focused_pane == pane_id {
                    workspace.focused_pane = first_pane_id(&workspace.root);
                }
                true
            }
        }
    }

    fn split_pane(&mut self, pane_id: u64, orientation: SplitOrientation) -> Option<u64> {
        let split_id = self.take_id();
        let new_pane = self.new_pane();
        let new_pane_id = new_pane.id;
        let workspace = self.active_workspace_mut();
        let target = find_node_of_pane(&mut workspace.root, pane_id)?;
        let old = std::mem::replace(target, Node::Pane(new_pane.clone()));
        *target = Node::Split {
            id: split_id,
            orientation,
            ratio: 0.5,
            first: Box::new(old),
            second: Box::new(Node::Pane(new_pane)),
        };
        workspace.focused_pane = new_pane_id;
        Some(new_pane_id)
    }

    fn snapshot(&self) -> StoreSnapshot {
        let active = self
            .workspaces
            .iter()
            .find(|w| w.id == self.active)
            .unwrap_or(&self.workspaces[0]);
        let mut nodes = Vec::new();
        flatten(&active.root, &mut nodes);
        let mut surfaces = Vec::new();
        for workspace in &self.workspaces {
            collect_surfaces(&workspace.root, &mut surfaces);
        }
        StoreSnapshot {
            workspaces: self
                .workspaces
                .iter()
                .map(|w| WorkspaceSummary {
                    id: w.id,
                    name: w.name.clone(),
                })
                .collect(),
            active_workspace: active.id,
            layout: WorkspaceLayout {
                workspace_id: active.id,
                root: node_id(&active.root),
                nodes,
                focused_pane: active.focused_pane,
            },
            surfaces,
        }
    }
}

fn default_tab_title() -> String {
    "Terminal".to_owned()
}

fn node_id(node: &Node) -> u64 {
    match node {
        Node::Pane(pane) => pane.id,
        Node::Split { id, .. } => *id,
    }
}

fn first_pane_id(node: &Node) -> u64 {
    match node {
        Node::Pane(pane) => pane.id,
        Node::Split { first, .. } => first_pane_id(first),
    }
}

fn find_pane(node: &mut Node, pane_id: u64) -> Option<&mut Pane> {
    match node {
        Node::Pane(pane) => (pane.id == pane_id).then_some(pane),
        Node::Split { first, second, .. } => {
            find_pane(first, pane_id).or_else(|| find_pane(second, pane_id))
        }
    }
}

fn find_pane_with_tab(node: &mut Node, tab_id: u64) -> Option<&mut Pane> {
    match node {
        Node::Pane(pane) => pane.tabs.iter().any(|t| t.id == tab_id).then_some(pane),
        Node::Split { first, second, .. } => {
            find_pane_with_tab(first, tab_id).or_else(|| find_pane_with_tab(second, tab_id))
        }
    }
}

fn find_node_of_pane(node: &mut Node, pane_id: u64) -> Option<&mut Node> {
    if matches!(node, Node::Pane(pane) if pane.id == pane_id) {
        return Some(node);
    }
    match node {
        Node::Pane(_) => None,
        Node::Split { first, second, .. } => {
            find_node_of_pane(first, pane_id).or_else(|| find_node_of_pane(second, pane_id))
        }
    }
}

fn set_ratio(node: &mut Node, split_id: u64, value: f32) -> bool {
    match node {
        Node::Pane(_) => false,
        Node::Split {
            id,
            ratio,
            first,
            second,
            ..
        } => {
            if *id == split_id {
                *ratio = value;
                return true;
            }
            set_ratio(first, split_id, value) || set_ratio(second, split_id, value)
        }
    }
}

enum Removal {
    NotFound,
    /// The removed pane was the whole tree.
    Emptied,
    Kept(Node),
}

/// Removes a pane; its parent split is replaced by the sibling.
fn remove_pane(node: Node, pane_id: u64) -> Removal {
    match node {
        Node::Pane(pane) if pane.id == pane_id => Removal::Emptied,
        Node::Pane(_) => Removal::NotFound,
        Node::Split {
            id,
            orientation,
            ratio,
            first,
            second,
        } => match remove_pane(*first.clone(), pane_id) {
            Removal::Emptied => Removal::Kept(*second),
            Removal::Kept(first) => Removal::Kept(Node::Split {
                id,
                orientation,
                ratio,
                first: Box::new(first),
                second,
            }),
            Removal::NotFound => match remove_pane(*second, pane_id) {
                Removal::Emptied => Removal::Kept(*first),
                Removal::Kept(second) => Removal::Kept(Node::Split {
                    id,
                    orientation,
                    ratio,
                    first,
                    second: Box::new(second),
                }),
                Removal::NotFound => Removal::NotFound,
            },
        },
    }
}

fn flatten(node: &Node, out: &mut Vec<LayoutNode>) {
    match node {
        Node::Pane(pane) => out.push(LayoutNode::Pane {
            id: pane.id,
            tabs: pane
                .tabs
                .iter()
                .map(|t| TabInfo {
                    id: t.id,
                    surface_id: t.surface_id,
                    title: t.title.clone(),
                })
                .collect(),
            active_tab: pane.active_tab,
        }),
        Node::Split {
            id,
            orientation,
            ratio,
            first,
            second,
        } => {
            out.push(LayoutNode::Split {
                id: *id,
                orientation: *orientation,
                ratio: *ratio,
                first: node_id(first),
                second: node_id(second),
            });
            flatten(first, out);
            flatten(second, out);
        }
    }
}

fn collect_surfaces(node: &Node, out: &mut Vec<u64>) {
    match node {
        Node::Pane(pane) => out.extend(pane.tabs.iter().map(|t| t.surface_id)),
        Node::Split { first, second, .. } => {
            collect_surfaces(first, out);
            collect_surfaces(second, out);
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn panes(snapshot: &StoreSnapshot) -> Vec<(u64, Vec<u64>, u64)> {
        snapshot
            .layout
            .nodes
            .iter()
            .filter_map(|n| match n {
                LayoutNode::Pane {
                    id,
                    tabs,
                    active_tab,
                } => Some((*id, tabs.iter().map(|t| t.id).collect(), *active_tab)),
                LayoutNode::Split { .. } => None,
            })
            .collect()
    }

    #[test]
    fn starts_with_one_workspace_holding_one_tab() {
        let store = WorkspaceStore::create();
        let snap = store.snapshot();
        assert_eq!(snap.workspaces.len(), 1);
        assert_eq!(snap.workspaces[0].name, "Workspace 1");
        let panes = panes(&snap);
        assert_eq!(panes.len(), 1);
        assert_eq!(panes[0].1.len(), 1);
        assert_eq!(snap.layout.focused_pane, panes[0].0);
        assert_eq!(snap.surfaces.len(), 1);
    }

    #[test]
    fn new_tab_is_selected_and_closing_it_selects_a_neighbour() {
        let store = WorkspaceStore::create();
        let pane = store.snapshot().layout.focused_pane;
        let first_tab = panes(&store.snapshot())[0].1[0];
        assert!(store.new_tab(pane).is_some());
        let (_, tabs, active) = panes(&store.snapshot())[0].clone();
        assert_eq!(tabs.len(), 2);
        assert_eq!(active, tabs[1]);
        assert!(store.close_tab(tabs[1]));
        assert_eq!(panes(&store.snapshot())[0].2, first_tab);
    }

    #[test]
    fn split_adds_a_focused_pane_and_closing_it_restores_the_tree() {
        let store = WorkspaceStore::create();
        let original = store.snapshot().layout.focused_pane;
        let new_pane = store
            .split_pane(original, SplitOrientation::SideBySide)
            .unwrap();
        let snap = store.snapshot();
        assert_eq!(snap.layout.focused_pane, new_pane);
        let LayoutNode::Split {
            first,
            second,
            orientation,
            ..
        } = &snap.layout.nodes[0]
        else {
            panic!("root must be a split: {:?}", snap.layout.nodes);
        };
        assert_eq!(
            (*first, *second, *orientation),
            (original, new_pane, SplitOrientation::SideBySide)
        );
        assert_eq!(snap.surfaces.len(), 2);

        let new_tab = panes(&snap)[1].1[0];
        assert!(store.close_tab(new_tab));
        let snap = store.snapshot();
        assert_eq!(snap.layout.root, original);
        assert_eq!(snap.layout.focused_pane, original);
        assert_eq!(snap.surfaces.len(), 1);
    }

    #[test]
    fn nested_split_removes_only_the_closed_pane() {
        let store = WorkspaceStore::create();
        let a = store.snapshot().layout.focused_pane;
        let b = store.split_pane(a, SplitOrientation::SideBySide).unwrap();
        let c = store.split_pane(b, SplitOrientation::Stacked).unwrap();
        assert!(store.close_pane(b));
        let ids: Vec<u64> = panes(&store.snapshot()).iter().map(|p| p.0).collect();
        assert_eq!(ids, vec![a, c]);
    }

    #[test]
    fn closing_the_last_tab_closes_the_workspace_and_keeps_one_around() {
        let store = WorkspaceStore::create();
        let first = store.snapshot().active_workspace;
        let second = store.new_workspace();
        assert_eq!(store.snapshot().active_workspace, second);
        let tab = panes(&store.snapshot())[0].1[0];
        assert!(store.close_tab(tab));
        let snap = store.snapshot();
        assert_eq!(snap.workspaces.len(), 1);
        assert_eq!(snap.active_workspace, first);

        assert!(store.close_workspace(first));
        let snap = store.snapshot();
        assert_eq!(snap.workspaces.len(), 1);
        assert_eq!(snap.workspaces[0].name, "Workspace 3");
    }

    #[test]
    fn select_workspace_switches_the_layout() {
        let store = WorkspaceStore::create();
        let first = store.snapshot().active_workspace;
        store.new_workspace();
        assert!(store.select_workspace(first));
        assert_eq!(store.snapshot().layout.workspace_id, first);
        assert!(!store.select_workspace(9999));
    }

    #[test]
    fn split_ratio_is_clamped() {
        let store = WorkspaceStore::create();
        let pane = store.snapshot().layout.focused_pane;
        store.split_pane(pane, SplitOrientation::Stacked);
        let split = store.snapshot().layout.root;
        assert!(store.set_split_ratio(split, 2.0));
        let LayoutNode::Split { ratio, .. } = store.snapshot().layout.nodes[0] else {
            panic!("root must be a split");
        };
        assert!((ratio - MAX_RATIO).abs() < f32::EPSILON);
    }
}
