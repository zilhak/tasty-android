//! Workspaces with the desktop's two layout levels: the upper layout places panes,
//! each pane has its own tab bar, and each tab has a lower layout that places surfaces.
//!
//! The store only tracks structure. Kotlin owns the terminal sessions and keys them by
//! surface id; after each change it compares [`StoreSnapshot::surfaces`] with its sessions.

use std::sync::{Arc, Mutex, MutexGuard};

use crate::tree::{Leaf, Removal, SplitOrientation, Tree};

/// One node of a tab's surface tree (lower layout).
#[derive(Debug, Clone, PartialEq, uniffi::Enum)]
pub enum SurfaceNode {
    Surface {
        id: u64,
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
pub struct TabInfo {
    pub id: u64,
    pub title: String,
    /// A name the user gave the tab. When set it is shown instead of the shell's title.
    pub custom_title: Option<String>,
    pub root: u64,
    pub nodes: Vec<SurfaceNode>,
    pub focused_surface: u64,
}

/// One node of a workspace's pane tree (upper layout).
#[derive(Debug, Clone, PartialEq, uniffi::Enum)]
pub enum PaneNode {
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
    pub nodes: Vec<PaneNode>,
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
struct Surface {
    id: u64,
}

impl Leaf for Surface {
    fn id(&self) -> u64 {
        self.id
    }
}

#[derive(Debug, Clone)]
struct Tab {
    id: u64,
    title: String,
    custom_title: Option<String>,
    layout: Tree<Surface>,
    focused_surface: u64,
}

#[derive(Debug, Clone)]
struct Pane {
    id: u64,
    tabs: Vec<Tab>,
    active_tab: u64,
}

impl Leaf for Pane {
    fn id(&self) -> u64 {
        self.id
    }
}

impl Pane {
    fn tab_with_surface(&mut self, surface_id: u64) -> Option<&mut Tab> {
        self.tabs.iter_mut().find(|t| t.layout.contains(surface_id))
    }
}

#[derive(Debug, Clone)]
struct Workspace {
    id: u64,
    name: String,
    layout: Tree<Pane>,
    focused_pane: u64,
}

impl Workspace {
    fn pane_mut(&mut self, pane_id: u64) -> Option<&mut Pane> {
        self.layout.find(&|p: &Pane| p.id == pane_id)
    }

    fn pane_with_tab(&mut self, tab_id: u64) -> Option<&mut Pane> {
        self.layout
            .find(&|p: &Pane| p.tabs.iter().any(|t| t.id == tab_id))
    }

    fn pane_with_surface(&mut self, surface_id: u64) -> Option<&mut Pane> {
        self.layout
            .find(&|p: &Pane| p.tabs.iter().any(|t| t.layout.contains(surface_id)))
    }
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

#[uniffi::export]
impl WorkspaceStore {
    #[uniffi::constructor]
    pub fn new() -> Arc<Self> {
        Arc::new(Self::create())
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

    /// Adds a tab with one surface to a pane, selects it and returns the surface id.
    pub fn new_tab(&self, pane_id: u64) -> Option<u64> {
        let mut state = self.lock();
        let tab = state.new_tab();
        let (tab_id, surface_id) = (tab.id, tab.focused_surface);
        let workspace = state.active_workspace_mut();
        let pane = workspace.pane_mut(pane_id)?;
        pane.tabs.push(tab);
        pane.active_tab = tab_id;
        workspace.focused_pane = pane_id;
        Some(surface_id)
    }

    /// Selects a tab and focuses its pane.
    pub fn select_tab(&self, tab_id: u64) -> bool {
        let mut state = self.lock();
        let workspace = state.active_workspace_mut();
        let Some(pane) = workspace.pane_with_tab(tab_id) else {
            return false;
        };
        pane.active_tab = tab_id;
        let pane_id = pane.id;
        workspace.focused_pane = pane_id;
        true
    }

    /// Names a tab. A name that is empty after trimming clears it, so the shell's title shows again.
    pub fn rename_tab(&self, tab_id: u64, title: String) -> bool {
        let mut state = self.lock();
        let workspace = state.active_workspace_mut();
        let Some(pane) = workspace.pane_with_tab(tab_id) else {
            return false;
        };
        let Some(tab) = pane.tabs.iter_mut().find(|t| t.id == tab_id) else {
            return false;
        };
        let title = title.trim();
        tab.custom_title = (!title.is_empty()).then(|| title.to_owned());
        true
    }

    /// Moves a tab by `offset` places within its pane. Returns false when the tab is not found
    /// or the move would leave the pane's tab row.
    pub fn move_tab(&self, tab_id: u64, offset: i32) -> bool {
        let mut state = self.lock();
        let workspace = state.active_workspace_mut();
        let Some(pane) = workspace.pane_with_tab(tab_id) else {
            return false;
        };
        let Some(from) = pane.tabs.iter().position(|t| t.id == tab_id) else {
            return false;
        };
        let to = i64::try_from(from).unwrap_or(i64::MAX) + i64::from(offset);
        let Ok(to) = usize::try_from(to) else {
            return false;
        };
        if offset == 0 || to >= pane.tabs.len() {
            return false;
        }
        let tab = pane.tabs.remove(from);
        pane.tabs.insert(to, tab);
        true
    }

    /// Closes a tab. An emptied pane closes, and an emptied workspace closes like
    /// [`WorkspaceStore::close_workspace`].
    pub fn close_tab(&self, tab_id: u64) -> bool {
        self.lock().close_tab(tab_id)
    }

    /// Splits a pane. The new pane gets one tab and the focus. Returns the new pane's id.
    pub fn split_pane(&self, pane_id: u64, orientation: SplitOrientation) -> Option<u64> {
        let mut state = self.lock();
        let (split_id, pane) = (state.take_id(), state.new_pane());
        let new_pane_id = pane.id;
        let workspace = state.active_workspace_mut();
        if !workspace.layout.split(pane_id, split_id, orientation, pane) {
            return None;
        }
        workspace.focused_pane = new_pane_id;
        Some(new_pane_id)
    }

    /// Closes a pane with all its tabs.
    pub fn close_pane(&self, pane_id: u64) -> bool {
        self.lock().close_pane(pane_id)
    }

    pub fn focus_pane(&self, pane_id: u64) -> bool {
        let mut state = self.lock();
        let workspace = state.active_workspace_mut();
        let found = workspace.pane_mut(pane_id).is_some();
        if found {
            workspace.focused_pane = pane_id;
        }
        found
    }

    /// Splits a surface inside its tab. The new surface gets the focus; returns its id.
    pub fn split_surface(&self, surface_id: u64, orientation: SplitOrientation) -> Option<u64> {
        let mut state = self.lock();
        let (split_id, new_id) = (state.take_id(), state.take_id());
        let workspace = state.active_workspace_mut();
        let tab = workspace
            .pane_with_surface(surface_id)?
            .tab_with_surface(surface_id)?;
        if !tab
            .layout
            .split(surface_id, split_id, orientation, Surface { id: new_id })
        {
            return None;
        }
        tab.focused_surface = new_id;
        Some(new_id)
    }

    /// Closes a surface. A tab left without surfaces closes like [`WorkspaceStore::close_tab`].
    pub fn close_surface(&self, surface_id: u64) -> bool {
        self.lock().close_surface(surface_id)
    }

    /// Focuses a surface, selecting its tab and focusing its pane.
    pub fn focus_surface(&self, surface_id: u64) -> bool {
        let mut state = self.lock();
        let workspace = state.active_workspace_mut();
        let Some(pane) = workspace.pane_with_surface(surface_id) else {
            return false;
        };
        let pane_id = pane.id;
        let Some(tab) = pane.tab_with_surface(surface_id) else {
            return false;
        };
        tab.focused_surface = surface_id;
        let tab_id = tab.id;
        pane.active_tab = tab_id;
        workspace.focused_pane = pane_id;
        true
    }

    /// Moves the divider of a pane split or a surface split. The ratio is clamped to 0.1..=0.9.
    pub fn set_split_ratio(&self, split_id: u64, ratio: f32) -> bool {
        let mut state = self.lock();
        let workspace = state.active_workspace_mut();
        if workspace.layout.set_ratio(split_id, ratio) {
            return true;
        }
        let mut changed = false;
        for_each_pane_mut(&mut workspace.layout, &mut |pane| {
            for tab in &mut pane.tabs {
                changed |= tab.layout.set_ratio(split_id, ratio);
            }
        });
        changed
    }
}

fn for_each_pane_mut(tree: &mut Tree<Pane>, f: &mut impl FnMut(&mut Pane)) {
    match tree {
        Tree::Leaf(pane) => f(pane),
        Tree::Split { first, second, .. } => {
            for_each_pane_mut(first, f);
            for_each_pane_mut(second, f);
        }
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

    fn new_tab(&mut self) -> Tab {
        let (tab_id, surface_id) = (self.take_id(), self.take_id());
        Tab {
            id: tab_id,
            title: "Terminal".to_owned(),
            custom_title: None,
            layout: Tree::Leaf(Surface { id: surface_id }),
            focused_surface: surface_id,
        }
    }

    fn new_pane(&mut self) -> Pane {
        let pane_id = self.take_id();
        let tab = self.new_tab();
        Pane {
            id: pane_id,
            active_tab: tab.id,
            tabs: vec![tab],
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
            layout: Tree::Leaf(pane),
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
        let Some(pane) = workspace.pane_with_tab(tab_id) else {
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
        match workspace.layout.clone().remove(pane_id) {
            Removal::NotFound => false,
            Removal::Emptied => {
                let id = workspace.id;
                self.close_workspace(id)
            }
            Removal::Kept(layout) => {
                workspace.layout = layout;
                if workspace.focused_pane == pane_id {
                    workspace.focused_pane = workspace.layout.first_leaf().id;
                }
                true
            }
        }
    }

    fn close_surface(&mut self, surface_id: u64) -> bool {
        let workspace = self.active_workspace_mut();
        let Some(tab) = workspace
            .pane_with_surface(surface_id)
            .and_then(|pane| pane.tab_with_surface(surface_id))
        else {
            return false;
        };
        match tab.layout.clone().remove(surface_id) {
            Removal::NotFound => false,
            Removal::Emptied => {
                let tab_id = tab.id;
                self.close_tab(tab_id)
            }
            Removal::Kept(layout) => {
                tab.layout = layout;
                if tab.focused_surface == surface_id {
                    tab.focused_surface = tab.layout.first_leaf().id;
                }
                true
            }
        }
    }

    fn snapshot(&self) -> StoreSnapshot {
        let active = self
            .workspaces
            .iter()
            .find(|w| w.id == self.active)
            .unwrap_or(&self.workspaces[0]);
        let mut nodes = Vec::new();
        active.layout.flatten(
            &pane_node,
            &|id, orientation, ratio, first, second| PaneNode::Split {
                id,
                orientation,
                ratio,
                first,
                second,
            },
            &mut nodes,
        );
        let surfaces = self
            .workspaces
            .iter()
            .flat_map(|w| w.layout.leaves())
            .flat_map(|pane| pane.tabs.iter())
            .flat_map(|tab| tab.layout.leaves())
            .map(|surface| surface.id)
            .collect();
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
                root: active.layout.node_id(),
                nodes,
                focused_pane: active.focused_pane,
            },
            surfaces,
        }
    }
}

fn pane_node(pane: &Pane) -> PaneNode {
    PaneNode::Pane {
        id: pane.id,
        tabs: pane.tabs.iter().map(tab_info).collect(),
        active_tab: pane.active_tab,
    }
}

fn tab_info(tab: &Tab) -> TabInfo {
    let mut nodes = Vec::new();
    tab.layout.flatten(
        &|surface: &Surface| SurfaceNode::Surface { id: surface.id },
        &|id, orientation, ratio, first, second| SurfaceNode::Split {
            id,
            orientation,
            ratio,
            first,
            second,
        },
        &mut nodes,
    );
    TabInfo {
        id: tab.id,
        title: tab.title.clone(),
        custom_title: tab.custom_title.clone(),
        root: tab.layout.node_id(),
        nodes,
        focused_surface: tab.focused_surface,
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn panes(snapshot: &StoreSnapshot) -> Vec<(u64, Vec<TabInfo>, u64)> {
        snapshot
            .layout
            .nodes
            .iter()
            .filter_map(|n| match n {
                PaneNode::Pane {
                    id,
                    tabs,
                    active_tab,
                } => Some((*id, tabs.clone(), *active_tab)),
                PaneNode::Split { .. } => None,
            })
            .collect()
    }

    fn first_tab(store: &WorkspaceStore) -> TabInfo {
        panes(&store.snapshot())[0].1[0].clone()
    }

    #[test]
    fn starts_with_one_workspace_holding_one_tab_and_surface() {
        let store = WorkspaceStore::create();
        let snap = store.snapshot();
        assert_eq!(snap.workspaces.len(), 1);
        assert_eq!(snap.workspaces[0].name, "Workspace 1");
        let panes = panes(&snap);
        assert_eq!(panes.len(), 1);
        let tab = &panes[0].1[0];
        assert_eq!(tab.nodes, vec![SurfaceNode::Surface { id: tab.root }]);
        assert_eq!(tab.focused_surface, tab.root);
        assert_eq!(snap.surfaces, vec![tab.root]);
    }

    #[test]
    fn renaming_a_tab_sets_and_clears_its_custom_title() {
        let store = WorkspaceStore::create();
        let tab = first_tab(&store).id;
        assert!(store.rename_tab(tab, "  build  ".to_owned()));
        assert_eq!(first_tab(&store).custom_title.as_deref(), Some("build"));
        assert!(store.rename_tab(tab, "   ".to_owned()));
        assert_eq!(first_tab(&store).custom_title, None);
        assert!(!store.rename_tab(9999, "x".to_owned()));
    }

    #[test]
    fn moving_a_tab_reorders_it_within_its_pane_and_stops_at_the_ends() {
        let store = WorkspaceStore::create();
        let pane = store.snapshot().layout.focused_pane;
        store.new_tab(pane);
        store.new_tab(pane);
        let ids = |s: &WorkspaceStore| -> Vec<u64> {
            panes(&s.snapshot())[0].1.iter().map(|t| t.id).collect()
        };
        let [a, b, c] = ids(&store)[..] else { panic!() };
        assert!(!store.move_tab(a, -1));
        assert!(!store.move_tab(c, 1));
        assert!(store.move_tab(a, 1));
        assert_eq!(ids(&store), vec![b, a, c]);
        assert!(store.move_tab(c, -2));
        assert_eq!(ids(&store), vec![c, b, a]);
    }

    #[test]
    fn new_tab_is_selected_and_closing_it_selects_a_neighbour() {
        let store = WorkspaceStore::create();
        let pane = store.snapshot().layout.focused_pane;
        let first = first_tab(&store).id;
        assert!(store.new_tab(pane).is_some());
        let (_, tabs, active) = panes(&store.snapshot())[0].clone();
        assert_eq!(tabs.len(), 2);
        assert_eq!(active, tabs[1].id);
        assert!(store.close_tab(tabs[1].id));
        assert_eq!(panes(&store.snapshot())[0].2, first);
    }

    #[test]
    fn pane_split_adds_a_focused_pane_and_closing_it_restores_the_tree() {
        let store = WorkspaceStore::create();
        let original = store.snapshot().layout.focused_pane;
        let new_pane = store
            .split_pane(original, SplitOrientation::SideBySide)
            .unwrap();
        let snap = store.snapshot();
        assert_eq!(snap.layout.focused_pane, new_pane);
        let PaneNode::Split { first, second, .. } = &snap.layout.nodes[0] else {
            panic!("root must be a split: {:?}", snap.layout.nodes);
        };
        assert_eq!((*first, *second), (original, new_pane));
        assert_eq!(snap.surfaces.len(), 2);

        assert!(store.close_tab(panes(&snap)[1].1[0].id));
        let snap = store.snapshot();
        assert_eq!(snap.layout.root, original);
        assert_eq!(snap.layout.focused_pane, original);
        assert_eq!(snap.surfaces.len(), 1);
    }

    #[test]
    fn surface_split_stays_inside_its_tab() {
        let store = WorkspaceStore::create();
        let pane = store.snapshot().layout.focused_pane;
        let surface = first_tab(&store).root;
        let added = store
            .split_surface(surface, SplitOrientation::Stacked)
            .unwrap();
        let tab = first_tab(&store);
        assert_eq!(tab.focused_surface, added);
        assert_eq!(tab.nodes.len(), 3);
        assert_eq!(store.snapshot().surfaces, vec![surface, added]);

        // A second tab does not see the first tab's split.
        store.new_tab(pane);
        let second = panes(&store.snapshot())[0].1[1].clone();
        assert_eq!(second.nodes.len(), 1);

        assert!(store.focus_surface(surface));
        let snap = store.snapshot();
        assert_eq!(panes(&snap)[0].2, tab.id);
        assert_eq!(first_tab(&store).focused_surface, surface);
    }

    #[test]
    fn closing_the_last_surface_closes_the_tab() {
        let store = WorkspaceStore::create();
        let pane = store.snapshot().layout.focused_pane;
        store.new_tab(pane);
        let first = first_tab(&store);
        let added = store
            .split_surface(first.root, SplitOrientation::SideBySide)
            .unwrap();
        assert!(store.close_surface(added));
        assert_eq!(first_tab(&store).nodes.len(), 1);
        assert!(store.close_surface(first.root));
        let tabs = &panes(&store.snapshot())[0].1;
        assert_eq!(tabs.len(), 1);
        assert_ne!(tabs[0].id, first.id);
    }

    #[test]
    fn nested_pane_split_removes_only_the_closed_pane() {
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
        assert!(store.close_tab(first_tab(&store).id));
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
    fn split_ratio_reaches_both_levels() {
        let store = WorkspaceStore::create();
        let pane = store.snapshot().layout.focused_pane;
        store.split_pane(pane, SplitOrientation::Stacked);
        let pane_split = store.snapshot().layout.root;
        assert!(store.set_split_ratio(pane_split, 0.3));

        let surface = first_tab(&store).root;
        store.split_surface(surface, SplitOrientation::SideBySide);
        let surface_split = first_tab(&store).root;
        assert!(store.set_split_ratio(surface_split, 0.7));
        let SurfaceNode::Split { ratio, .. } = first_tab(&store).nodes[0] else {
            panic!("tab root must be a split");
        };
        assert!((ratio - 0.7).abs() < f32::EPSILON);
        assert!(!store.set_split_ratio(9999, 0.5));
    }
}
