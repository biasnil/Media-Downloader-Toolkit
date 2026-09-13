"""Right-click Cut/Copy/Paste/Select All menu for CTkEntry and CTkTextbox
widgets. CustomTkinter draws its own border/corners around a plain
tkinter.Entry/tkinter.Text underneath, and that plain widget doesn't come
with a right-click context menu the way native OS text fields do (Ctrl+V
still works as a keyboard shortcut either way -- this just adds the
right-click route too)."""

import tkinter as tk


def add_context_menu(widget):
    """Attaches the menu to a CTkEntry or CTkTextbox. Safe no-op on
    anything else (looks for a ._entry or ._textbox attribute; does
    nothing if neither is found)."""
    target = getattr(widget, "_entry", None) or getattr(widget, "_textbox", None)
    if target is None:
        return

    menu = tk.Menu(target, tearoff=0)

    def send(event_name):
        def handler():
            try:
                target.event_generate(event_name)
            except Exception:
                pass
        return handler

    def select_all():
        try:
            if isinstance(target, tk.Entry):
                target.select_range(0, "end")
                target.icursor("end")
            else:
                target.tag_add("sel", "1.0", "end")
        except Exception:
            pass

    menu.add_command(label="Cut", command=send("<<Cut>>"))
    menu.add_command(label="Copy", command=send("<<Copy>>"))
    menu.add_command(label="Paste", command=send("<<Paste>>"))
    menu.add_separator()
    menu.add_command(label="Select All", command=select_all)

    def show_menu(event):
        try:
            menu.tk_popup(event.x_root, event.y_root)
        finally:
            menu.grab_release()

    target.bind("<Button-3>", show_menu)  # Windows/Linux right-click
    target.bind("<Button-2>", show_menu)  # macOS right-click