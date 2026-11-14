//! Converts the terminal grid into runs of equally styled text for drawing.

use tasty_terminal::{ScrollbackLine, Terminal, TerminalViewport};
use termwiz::cell::{CellAttributes, Intensity, Underline, unicode_column_width};
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

/// The rows shown by a viewport, which may reach back into the scrollback.
/// Rows hold only spans that draw something.
#[derive(Debug, Clone, uniffi::Record)]
pub struct ScreenSnapshot {
    pub cols: u32,
    pub rows: u32,
    pub lines: Vec<Vec<TextSpan>>,
    pub cursor_col: u32,
    /// Cursor row within the shown rows. Meaningful only when `cursor_visible`.
    pub cursor_row: u32,
    pub cursor_visible: bool,
    /// Rows the view is scrolled back from the live screen; 0 follows new output.
    pub scroll_offset: u32,
    /// Rows of history that can be scrolled back to.
    pub history_len: u32,
    /// The shell has exited; the screen keeps its last contents.
    pub exited: bool,
}

/// One cell before styling: column, text, width in columns, attributes.
type CellRefParts<'a> = (usize, &'a str, usize, &'a CellAttributes);

pub(crate) fn capture(
    terminal: &Terminal,
    viewport: &TerminalViewport,
    exited: bool,
) -> ScreenSnapshot {
    terminal.with_view(viewport, |view| {
        let info = view.viewport();
        let (cut, top) = (info.cut, info.top_row);
        let screen = view.surface().screen_lines();
        let lines = (top..top + cut.rows)
            .map(|row| {
                if row < cut.screen_start {
                    view.scrollback_line(row)
                        .map(|line| history_spans(&line))
                        .unwrap_or_default()
                } else {
                    screen
                        .get(row - cut.screen_start)
                        .map(|line| live_spans(line))
                        .unwrap_or_default()
                }
            })
            .collect();
        let offset = info.scroll_offset();
        let (cursor_col, cursor_row) = view.cursor_position();
        let shown_row = cursor_row + offset;
        ScreenSnapshot {
            cols: to_u32(cut.cols),
            rows: to_u32(cut.rows),
            lines,
            cursor_col: to_u32(cursor_col),
            cursor_row: to_u32(shown_row),
            cursor_visible: view.cursor_visible() && !exited && shown_row < cut.rows,
            scroll_offset: to_u32(offset),
            history_len: to_u32(cut.history_len()),
            exited,
        }
    })
}

fn live_spans(line: &Line) -> Vec<TextSpan> {
    let cells: Vec<_> = line.visible_cells().collect();
    spans(
        cells
            .iter()
            .map(|c| (c.cell_index(), c.str(), c.width(), c.attrs())),
    )
}

/// History rows store one entry per character, so a wide character is not followed by a
/// placeholder. Columns are counted from the widths.
fn history_spans(line: &ScrollbackLine) -> Vec<TextSpan> {
    let mut col = 0;
    spans(line.cells().map(|(text, attrs)| {
        let width = unicode_column_width(text, None).max(1);
        let start = col;
        col += width;
        (start, text, width, attrs)
    }))
}

fn spans<'a>(cells: impl Iterator<Item = CellRefParts<'a>>) -> Vec<TextSpan> {
    let mut spans: Vec<TextSpan> = Vec::new();
    for (col, text, width, attrs) in cells {
        let (fg, bg, flags) = (
            color(attrs.foreground()),
            color(attrs.background()),
            flags(attrs),
        );
        let width = to_u32(width.max(1));
        let col = to_u32(col);
        let text = if text.is_empty() { " " } else { text };
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
        capture(&terminal, &TerminalViewport::LIVE, false)
    }

    fn text(spans: &[TextSpan]) -> String {
        let (mut out, mut col) = (String::new(), 0);
        for span in spans {
            while col < span.col {
                out.push(' ');
                col += 1;
            }
            out.push_str(&span.text);
            col += span.cells;
        }
        out.trim_end().to_owned()
    }

    /// Five numbered lines on a three-row screen leave lines 1..=3 in the history.
    fn scrolled_terminal() -> Terminal {
        let mut terminal = Terminal::new_detached(20, 3);
        terminal.feed_bytes(
            b"line1\r\n\x1b[41mline2\x1b[0m\r\nline3 \xed\x95\x9c\xea\xb8\x80 x\r\nline4\r\nline5",
        );
        terminal
    }

    #[test]
    fn scrolling_back_shows_history_rows_with_their_style() {
        let terminal = scrolled_terminal();
        let live = capture(&terminal, &TerminalViewport::LIVE, false);
        assert_eq!(live.history_len, 2);
        assert_eq!(text(&live.lines[0]), "line3 한글 x");
        assert!(live.cursor_visible);

        let mut viewport = TerminalViewport::LIVE;
        viewport.scroll_up(terminal.content_cut(), 2);
        let back = capture(&terminal, &viewport, false);
        assert_eq!(back.scroll_offset, 2);
        let rows: Vec<String> = back.lines.iter().map(|l| text(l)).collect();
        assert_eq!(rows, vec!["line1", "line2", "line3 한글 x"]);
        assert_eq!(back.lines[1][0].bg, CellColor::Palette { index: 1 });
        // The cursor is on line5, which is below the shown rows.
        assert!(!back.cursor_visible);
    }

    #[test]
    fn wide_characters_in_history_keep_their_columns() {
        let mut terminal = scrolled_terminal();
        terminal.feed_bytes(b"\r\nline6");
        let mut viewport = TerminalViewport::LIVE;
        viewport.scroll_up(terminal.content_cut(), 3);
        let snap = capture(&terminal, &viewport, false);
        let row = &snap.lines[2];
        assert_eq!(text(row), "line3 한글 x");
        let wide: Vec<(u32, u32)> = row
            .iter()
            .filter(|s| s.cells == 2)
            .map(|s| (s.col, s.cells))
            .collect();
        assert_eq!(wide, vec![(6, 2), (8, 2)]);
        let x = row.iter().find(|s| s.text.contains('x')).unwrap();
        assert_eq!(x.col + x.text.find('x').unwrap() as u32, 11);
    }

    #[test]
    fn scroll_offset_is_clamped_to_the_history() {
        let terminal = scrolled_terminal();
        let mut viewport = TerminalViewport::LIVE;
        viewport.scroll_up(terminal.content_cut(), 100);
        assert_eq!(capture(&terminal, &viewport, false).scroll_offset, 2);
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
