//! Binary split tree shared by both layout levels: panes in a workspace (upper layout)
//! and surfaces in a tab (lower layout).

pub(crate) const MIN_RATIO: f32 = 0.1;
pub(crate) const MAX_RATIO: f32 = 0.9;

/// How a split arranges its two children.
#[derive(Debug, Clone, Copy, PartialEq, Eq, uniffi::Enum)]
pub enum SplitOrientation {
    /// First child on the left, second on the right.
    SideBySide,
    /// First child on top, second below.
    Stacked,
}

/// A leaf has an id unique within the store.
pub(crate) trait Leaf {
    fn id(&self) -> u64;
}

#[derive(Debug, Clone)]
pub(crate) enum Tree<L> {
    Leaf(L),
    Split {
        id: u64,
        orientation: SplitOrientation,
        ratio: f32,
        first: Box<Tree<L>>,
        second: Box<Tree<L>>,
    },
}

/// Result of removing a leaf.
pub(crate) enum Removal<L> {
    NotFound,
    /// The removed leaf was the whole tree.
    Emptied,
    Kept(Tree<L>),
}

impl<L: Leaf + Clone> Tree<L> {
    pub(crate) fn node_id(&self) -> u64 {
        match self {
            Self::Leaf(leaf) => leaf.id(),
            Self::Split { id, .. } => *id,
        }
    }

    pub(crate) fn first_leaf(&self) -> &L {
        match self {
            Self::Leaf(leaf) => leaf,
            Self::Split { first, .. } => first.first_leaf(),
        }
    }

    pub(crate) fn leaves(&self) -> Vec<&L> {
        let mut out = Vec::new();
        self.collect_leaves(&mut out);
        out
    }

    fn collect_leaves<'a>(&'a self, out: &mut Vec<&'a L>) {
        match self {
            Self::Leaf(leaf) => out.push(leaf),
            Self::Split { first, second, .. } => {
                first.collect_leaves(out);
                second.collect_leaves(out);
            }
        }
    }

    pub(crate) fn find(&mut self, pred: &impl Fn(&L) -> bool) -> Option<&mut L> {
        match self {
            Self::Leaf(leaf) => pred(leaf).then_some(leaf),
            Self::Split { first, second, .. } => first.find(pred).or_else(|| second.find(pred)),
        }
    }

    pub(crate) fn contains(&self, leaf_id: u64) -> bool {
        self.leaves().iter().any(|l| l.id() == leaf_id)
    }

    /// Replaces the leaf with a split holding the old leaf first and `new_leaf` second.
    pub(crate) fn split(
        &mut self,
        leaf_id: u64,
        split_id: u64,
        orientation: SplitOrientation,
        new_leaf: L,
    ) -> bool {
        let Some(target) = self.node_of_leaf(leaf_id) else {
            return false;
        };
        let old = std::mem::replace(target, Self::Leaf(new_leaf.clone()));
        *target = Self::Split {
            id: split_id,
            orientation,
            ratio: 0.5,
            first: Box::new(old),
            second: Box::new(Self::Leaf(new_leaf)),
        };
        true
    }

    fn node_of_leaf(&mut self, leaf_id: u64) -> Option<&mut Self> {
        if matches!(self, Self::Leaf(leaf) if leaf.id() == leaf_id) {
            return Some(self);
        }
        match self {
            Self::Leaf(_) => None,
            Self::Split { first, second, .. } => first
                .node_of_leaf(leaf_id)
                .or_else(|| second.node_of_leaf(leaf_id)),
        }
    }

    /// Sets a split's ratio, clamped to `MIN_RATIO..=MAX_RATIO`.
    pub(crate) fn set_ratio(&mut self, split_id: u64, value: f32) -> bool {
        match self {
            Self::Leaf(_) => false,
            Self::Split {
                id,
                ratio,
                first,
                second,
                ..
            } => {
                if *id == split_id {
                    *ratio = value.clamp(MIN_RATIO, MAX_RATIO);
                    return true;
                }
                first.set_ratio(split_id, value) || second.set_ratio(split_id, value)
            }
        }
    }

    /// Removes a leaf; its parent split is replaced by the sibling.
    pub(crate) fn remove(self, leaf_id: u64) -> Removal<L> {
        match self {
            Self::Leaf(leaf) if leaf.id() == leaf_id => Removal::Emptied,
            Self::Leaf(_) => Removal::NotFound,
            Self::Split {
                id,
                orientation,
                ratio,
                first,
                second,
            } => {
                let rebuild = |first, second| {
                    Removal::Kept(Self::Split {
                        id,
                        orientation,
                        ratio,
                        first: Box::new(first),
                        second: Box::new(second),
                    })
                };
                match first.clone().remove(leaf_id) {
                    Removal::Emptied => Removal::Kept(*second),
                    Removal::Kept(kept) => rebuild(kept, *second),
                    Removal::NotFound => match second.remove(leaf_id) {
                        Removal::Emptied => Removal::Kept(*first),
                        Removal::Kept(kept) => rebuild(*first, kept),
                        Removal::NotFound => Removal::NotFound,
                    },
                }
            }
        }
    }

    /// Lists nodes depth first. Splits refer to their children by node id.
    pub(crate) fn flatten<N>(
        &self,
        leaf: &impl Fn(&L) -> N,
        split: &impl Fn(u64, SplitOrientation, f32, u64, u64) -> N,
        out: &mut Vec<N>,
    ) {
        match self {
            Self::Leaf(l) => out.push(leaf(l)),
            Self::Split {
                id,
                orientation,
                ratio,
                first,
                second,
            } => {
                out.push(split(
                    *id,
                    *orientation,
                    *ratio,
                    first.node_id(),
                    second.node_id(),
                ));
                first.flatten(leaf, split, out);
                second.flatten(leaf, split, out);
            }
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[derive(Debug, Clone, PartialEq)]
    struct L(u64);

    impl Leaf for L {
        fn id(&self) -> u64 {
            self.0
        }
    }

    fn ids(tree: &Tree<L>) -> Vec<u64> {
        tree.leaves().iter().map(|l| l.0).collect()
    }

    #[test]
    fn split_then_remove_restores_the_original() {
        let mut tree = Tree::Leaf(L(1));
        assert!(tree.split(1, 10, SplitOrientation::SideBySide, L(2)));
        assert!(tree.split(2, 11, SplitOrientation::Stacked, L(3)));
        assert_eq!(ids(&tree), vec![1, 2, 3]);
        let Removal::Kept(tree) = tree.remove(2) else {
            panic!("leaf 2 must be removed");
        };
        assert_eq!(ids(&tree), vec![1, 3]);
        let Removal::Kept(tree) = tree.remove(3) else {
            panic!("leaf 3 must be removed");
        };
        assert!(matches!(tree, Tree::Leaf(L(1))));
        assert!(matches!(tree.remove(1), Removal::Emptied));
    }

    #[test]
    fn missing_leaf_is_reported() {
        let mut tree = Tree::Leaf(L(1));
        assert!(!tree.split(5, 10, SplitOrientation::Stacked, L(2)));
        assert!(matches!(tree.remove(5), Removal::NotFound));
    }

    #[test]
    fn ratio_is_clamped() {
        let mut tree = Tree::Leaf(L(1));
        tree.split(1, 10, SplitOrientation::Stacked, L(2));
        assert!(tree.set_ratio(10, 2.0));
        let Tree::Split { ratio, .. } = tree else {
            panic!("root must be a split");
        };
        assert!((ratio - MAX_RATIO).abs() < f32::EPSILON);
    }
}
