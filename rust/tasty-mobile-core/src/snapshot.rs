//! Converts the terminal grid into runs of equally styled text for drawing.

use tasty_terminal::Terminal;
use termwiz::cell::{CellAttributes, Intensity, Underline};
use termwiz::color::ColorAttribute;
use termwiz::surface::line::Line;

pub const FLAG_BOLD: u8 = 1;
pub const FLAG_DIM: u8 = 1 << 1;
pub const FLAG_ITALIC: u8 = 1 << 2;
pub const FLAG_UNDERLINE: u8 = 1 << 3;
pub const FLAG_STRIKETHROUGH: u8 = 1 << 4;
pub const FLAG_REVERSE: u8 = 1 << 5;
pub const FLAG_INVISIBLE: u8 = 1 << 6;

/// A cell color as the shell asked for it. Kotlin maps it to the theme.
#[derive(Debug, Clone, Copy, PartialEq, Eq, uniffi::Enum)]
pub enum CellColor {
    Default,
    Palette { index: u8 },
    Rgb { r: u8, g: u8, b: u8 },
}

/// Consecutive cells on one row that share a style. A wide character is always its own span.
#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct TextSpan {
    /// First column of the span.
    pub col: u32,
    /// Number of columns the span covers.
    pub cells: u32,
    pub text: String,
    pub fg: CellColor,
    pub bg: CellColor,
    /// Bit set of the `FLAG_*` values.
    pub flags: u8,
}

/// The visible screen. Rows hold only spans that draw something.
#[derive(Debug, Clone, uniffi::Record)]
pub struct ScreenSnapshot {
    pub cols: u32,
    pub rows: u32,
    pub lines: Vec<Vec<TextSpan>>,
    pub cursor_col: u32,
    pub cursor_row: u32,
    pub cursor_visible: bool,
    /// The shell has exited; the screen keeps its last contents.
    pub exited: bool,
}

pub(crate) fn capture(terminal: &Terminal, exited: bool) -> ScreenSnapshot {
    let (cols, rows) = terminal.dimensions();
    let (cursor_col, cursor_row) = terminal.cursor_position();
    let lines = terminal.screen_lines().iter().map(line_spans).collect();
    ScreenSnapshot {
        cols: to_u32(cols),
        rows: to_u32(rows),
        lines,
        cursor_col: to_u32(cursor_col),
        cursor_row: to_u32(cursor_row),
        cursor_visible: terminal.cursor_visible() && !exited,
        exited,
    }
}

fn line_spans(line: &Line) -> Vec<TextSpan> {
    let mut spans: Vec<TextSpan> = Vec::new();
    for cell in line.visible_cells() {
        let attrs = cell.attrs();
        let (fg, bg, flags) = (
            color(attrs.foreground()),
            color(attrs.background()),
            flags(attrs),
        );
        let width = to_u32(cell.width().max(1));
        let col = to_u32(cell.cell_index());
        let text = if cell.str().is_empty() {
            " "
        } else {
            cell.str()
        };
        let joinable = spans.last().is_some_and(|last| {
            width == 1
                && last.cells == to_u32(last.text.chars().count())
                && last.col + last.cells == col
                && (last.fg, last.bg, last.flags) == (fg, bg, flags)
        });
        match spans.last_mut() {
            Some(last) if joinable => {
                last.text.push_str(text);
                last.cells += 1;
            }
            _ => spans.push(TextSpan {
                col,
                cells: width,
                text: text.to_owned(),
                fg,
                bg,
                flags,
            }),
        }
    }
    spans.retain(draws_something);
    spans
}

/// Blank text on the default background with no line decoration leaves the cell empty.
fn draws_something(span: &TextSpan) -> bool {
    let decorated = span.flags & (FLAG_UNDERLINE | FLAG_STRIKETHROUGH | FLAG_REVERSE) != 0;
    decorated || span.bg != CellColor::Default || span.text.chars().any(|c| c != ' ')
}

fn color(attr: ColorAttribute) -> CellColor {
    match attr {
        ColorAttribute::Default => CellColor::Default,
        ColorAttribute::PaletteIndex(index) => CellColor::Palette { index },
        ColorAttribute::TrueColorWithPaletteFallback(rgba, _)
        | ColorAttribute::TrueColorWithDefaultFallback(rgba) => CellColor::Rgb {
            r: channel(rgba.0),
            g: channel(rgba.1),
            b: channel(rgba.2),
        },
    }
}

fn channel(value: f32) -> u8 {
    // The value is clamped to 0..=255 first, so the cast cannot truncate.
    (value.clamp(0.0, 1.0) * 255.0).round() as u8
}

fn flags(attrs: &CellAttributes) -> u8 {
    let mut flags = 0;
    match attrs.intensity() {
        Intensity::Bold => flags |= FLAG_BOLD,
        Intensity::Half => flags |= FLAG_DIM,
        Intensity::Normal => {}
    }
    let pairs = [
        (attrs.italic(), FLAG_ITALIC),
        (attrs.underline() != Underline::None, FLAG_UNDERLINE),
        (attrs.strikethrough(), FLAG_STRIKETHROUGH),
        (attrs.reverse(), FLAG_REVERSE),
        (attrs.invisible(), FLAG_INVISIBLE),
    ];
    for (set, flag) in pairs {
        if set {
            flags |= flag;
        }
    }
    flags
}

fn to_u32(value: usize) -> u32 {
    u32::try_from(value).unwrap_or(u32::MAX)
}

#[cfg(test)]
mod tests {
    use super::*;

    fn screen(input: &str) -> ScreenSnapshot {
        let mut terminal = Terminal::new_detached(20, 3);
        terminal.feed_bytes(input.as_bytes());
        capture(&terminal, false)
    }

    #[test]
    fn plain_text_becomes_one_span_and_blank_rows_are_empty() {
        let snap = screen("hello world");
        assert_eq!((snap.cols, snap.rows), (20, 3));
        assert_eq!(snap.lines[0].len(), 1);
        let span = &snap.lines[0][0];
        assert_eq!((span.col, span.text.trim_end()), (0, "hello world"));
        assert!(snap.lines[1].is_empty());
        assert_eq!((snap.cursor_col, snap.cursor_row), (11, 0));
    }

    #[test]
    fn style_changes_split_spans() {
        let snap = screen("a\x1b[1;31mb\x1b[0;44mc\x1b[0m");
        let spans = &snap.lines[0];
        assert_eq!(spans[0].text, "a");
        assert_eq!(spans[1].text, "b");
        assert_eq!(spans[1].fg, CellColor::Palette { index: 1 });
        assert_eq!(spans[1].flags & FLAG_BOLD, FLAG_BOLD);
        assert_eq!(spans[2].text, "c");
        assert_eq!(spans[2].bg, CellColor::Palette { index: 4 });
    }

    #[test]
    fn true_color_is_kept_as_rgb() {
        let snap = screen("\x1b[38;2;10;20;30mx");
        assert_eq!(
            snap.lines[0][0].fg,
            CellColor::Rgb {
                r: 10,
                g: 20,
                b: 30
            }
        );
    }

    #[test]
    fn wide_characters_get_their_own_span_with_two_cells() {
        let snap = screen("a한b");
        let spans = &snap.lines[0];
        assert_eq!(spans[0].text, "a");
        assert_eq!(
            (spans[1].text.as_str(), spans[1].col, spans[1].cells),
            ("한", 1, 2)
        );
        assert_eq!((spans[2].col, spans[2].text.chars().next()), (3, Some('b')));
    }
}
