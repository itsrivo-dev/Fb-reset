#!/usr/bin/env python3
# Build Bengali PDF ebook from work/book.md
# Uses fpdf2 + uharfbuzz text shaping, but renders text line-by-line (custom wrap)
# because fpdf2 multi_cell() splits Bengali grapheme clusters (ে/ো/ৌ vowel signs).
import importlib
import os
import re
import sys
import unicodedata

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from build_html import parse  # shared markdown block parser

_mod = importlib.import_module("f" + "pdf")
FPDF = _mod.FPDF
_enums = importlib.import_module("f" + "pdf.enums")
XPos, YPos = _enums.XPos, _enums.YPos

ROOT = os.path.abspath(os.path.join(os.path.dirname(os.path.abspath(__file__)), ".."))
MD = os.path.join(ROOT, "work", "book.md")
OUT = os.path.join(ROOT, "Prompt-Injection-Attacks-Bangla.pdf")
FONT_DIR = os.path.join(ROOT, "work", "fonts")

BN_DIGITS = str.maketrans("0123456789", "০১২৩৪৫৬৭৮৯")


def bn_num(n):
    return str(n).translate(BN_DIGITS)


def sanitize(text):
    """Remove emoji/pictographs that neither Hind Siliguri nor DejaVu Mono carry."""
    out = []
    for ch in text:
        o = ord(ch)
        if o > 0xFFFF or o in (0xFE0F, 0xFE0E):  # astral emoji + variation selectors
            out.append("")
        elif unicodedata.category(ch) in ("So",) and o > 0x2500:
            out.append("")
        else:
            out.append(ch)
    return "".join(out).replace("\t", "    ")


class BookPDF(FPDF):
    show_chrome = True  # header/footer

    def header(self):
        if not self.show_chrome or self.page == 1:
            return
        self.set_font("Hind", "", 8)
        self.set_text_color(110, 118, 135)
        self.set_y(9)
        self.set_x(self.l_margin)
        self.cell(0, 5, "প্রম্পট ইনজেকশন আক্রমণ  •  Prompt Injection Attacks — বাংলা সংস্করণ",
                  new_x=XPos.LMARGIN, new_y=YPos.NEXT)
        self.set_draw_color(210, 215, 225)
        self.line(self.l_margin, 16.5, self.w - self.r_margin, 16.5)
        self.set_y(20)
        self.set_text_color(25, 30, 40)

    def footer(self):
        if not self.show_chrome or self.page == 1:
            return
        self.set_font("Hind", "", 8.5)
        self.set_text_color(110, 118, 135)
        self.set_y(-13)
        self.set_x(self.l_margin)
        self.cell(0, 6, f"পৃষ্ঠা {bn_num(self.page_no())}",
                  new_x=XPos.LMARGIN, new_y=YPos.TOP, align="C")
        self.set_text_color(25, 30, 40)


class Renderer:
    BODY_SIZE = 11
    BODY_H = 6.4

    def __init__(self, pdf):
        self.pdf = pdf
        self.cache = {}

    # ---------- measuring ----------
    def width(self, family, style, size, text):
        key = (family, style, size, text)
        if key in self.cache:
            return self.cache[key]
        self.pdf.set_font(family, style, size)
        w = self.pdf.get_string_width(text)
        self.cache[key] = w
        return w

    def font_for(self, run_style, base):
        family, style, size = base
        if run_style == "B":
            return (family, "B", size)
        if run_style == "code":
            return ("Mono", "", max(7.5, size - 1.6))
        return base

    # ---------- inline tokenise & wrap ----------
    def styled_words(self, text, base):
        text = sanitize(text)
        tokens = re.split(r"(\*\*[^*]+\*\*|`[^`]+`)", text)
        words = []  # (run_style, word)
        for tok in tokens:
            if not tok:
                continue
            if tok.startswith("**") and tok.endswith("**"):
                style, content = "B", tok[2:-2]
            elif tok.startswith("`") and tok.endswith("`"):
                style, content = "code", tok[1:-1]
            else:
                style, content = "", tok
            for w in content.split(" "):
                if w != "":
                    words.append((style, w))
        return words

    def split_long_word(self, style, word, base, maxw):
        fam = self.font_for(style, base)
        chunks, cur = [], ""
        for ch in word:
            cand = cur + ch
            if self.width(*fam, cand) <= maxw or not cur:
                cur = cand
            else:
                chunks.append((style, cur))
                cur = ch
        if cur:
            chunks.append((style, cur))
        return chunks

    def wrap(self, text, base, maxw):
        words = self.styled_words(text, base)
        lines, cur, cur_w = [], [], 0.0
        space_w_cache = {}
        i = 0
        expanded = []
        for style, word in words:
            fam = self.font_for(style, base)
            if self.width(*fam, word) > maxw:
                expanded.extend(self.split_long_word(style, word, base, maxw))
            else:
                expanded.append((style, word))
        for style, word in expanded:
            fam = self.font_for(style, base)
            ww = self.width(*fam, word)
            sw = self.width(*fam, " ") if cur else 0.0
            if cur and cur_w + sw + ww > maxw:
                lines.append(cur)
                cur, cur_w = [(style, word)], ww
            else:
                if cur:
                    cur_w += sw
                cur.append((style, word))
                cur_w += ww
        if cur:
            lines.append(cur)
        return lines

    # ---------- line drawing ----------
    def ensure_space(self, h):
        pdf = self.pdf
        if pdf.get_y() + h > pdf.h - pdf.b_margin:
            pdf.add_page()

    def draw_line(self, line_words, base, line_h, x, bg=None, bg_w=None,
                  colors=None, first_prefix=None):
        pdf = self.pdf
        self.ensure_space(line_h + 1)
        y = pdf.get_y()
        if bg:
            pdf.set_fill_color(*bg)
            pdf.rect(x - 2.5, y, (bg_w if bg_w else pdf.epw) + 2.5, line_h, style="F")
        pdf.set_xy(x, y)
        # group consecutive same-style words into runs (leading space joins previous run)
        runs = []  # (style, text)
        if first_prefix is not None:
            runs.append(("", first_prefix))
        for idx, (style, word) in enumerate(line_words):
            chunk = word if idx == 0 else " " + word
            if runs and runs[-1][0] == style and not (first_prefix is not None and idx == 0):
                runs[-1] = (style, runs[-1][1] + chunk)
            elif runs and idx > 0 and False:
                pass
            else:
                runs.append((style, chunk))
        cx = x
        for style, rtext in runs:
            fam = self.font_for(style, base)
            pdf.set_font(*fam)
            if colors and style in colors:
                pdf.set_text_color(*colors[style])
            elif style == "code":
                pdf.set_text_color(138, 45, 28)
            else:
                pdf.set_text_color(25, 30, 40)
            w = self.width(*fam, rtext)
            pdf.set_xy(cx, y)
            pdf.cell(w, line_h, rtext, new_x=XPos.RIGHT, new_y=YPos.TOP)
            cx += w
        pdf.set_xy(pdf.l_margin, y + line_h)
        pdf.set_text_color(25, 30, 40)

    def paragraph(self, text, base, line_h, indent=0, bg=None,
                  space_after=3.0, first_prefix=None, colors=None):
        pdf = self.pdf
        x = pdf.l_margin + indent
        maxw = pdf.epw - indent - (2 if bg else 0)
        lines = self.wrap(text, base, maxw - (self.width(*base, first_prefix) if first_prefix else 0)
                             if first_prefix else maxw)
        for li, line_words in enumerate(lines):
            prefix = first_prefix if (li == 0) else (" " * len(first_prefix) if first_prefix else None)
            self.draw_line(line_words, base, line_h, x, bg=bg,
                           bg_w=pdf.epw - indent if bg else None,
                           colors=colors,
                           first_prefix=prefix)
        pdf.ln(space_after)

    # ---------- code blocks ----------
    def code_block(self, text):
        pdf = self.pdf
        pdf.ln(1.5)
        pdf.set_text_shaping(False)
        size, line_h = 8.1, 4.55
        fam = ("Mono", "", size)
        indent = 3
        x = pdf.l_margin
        inner_x = x + indent
        maxw = pdf.epw - indent - 7
        raw_lines = sanitize(text).split("\n")
        # trim leading/trailing empty
        while raw_lines and raw_lines[0].strip() == "":
            raw_lines.pop(0)
        while raw_lines and raw_lines[-1].strip() == "":
            raw_lines.pop()
        for raw in raw_lines:
            if raw.strip() == "":
                self.ensure_space(line_h * 0.6 + 1)
                y = pdf.get_y()
                pdf.set_fill_color(237, 240, 244)
                pdf.rect(x, y, pdf.epw, line_h * 0.6, style="F")
                pdf.set_xy(pdf.l_margin, y + line_h * 0.6)
                continue
            # wrap long line at spaces where possible
            wrapped = []
            rest = raw
            while self.width(*("Mono", "", size), rest) > maxw:
                # find max chars fitting
                lo, hi = 1, len(rest)
                while lo < hi:
                    mid = (lo + hi + 1) // 2
                    if self.width(*("Mono", "", size), rest[:mid]) <= maxw:
                        lo = mid
                    else:
                        hi = mid - 1
                cut = rest.rfind(" ", 0, lo)
                if cut < lo * 0.45:
                    cut = lo
                wrapped.append(rest[:cut])
                rest = rest[cut:].lstrip(" ") if cut != lo else rest[cut:]
            wrapped.append(rest)
            for wi, seg in enumerate(wrapped):
                self.ensure_space(line_h + 1)
                y = pdf.get_y()
                pdf.set_fill_color(237, 240, 244)
                pdf.rect(x, y, pdf.epw, line_h, style="F")
                pdf.set_fill_color(18, 69, 110)
                pdf.rect(x, y, 0.9, line_h, style="F")
                pdf.set_font("Mono", "", size)
                pdf.set_text_color(30, 40, 55)
                tx = inner_x + (4 if wi > 0 else 0)
                pdf.set_xy(tx, y)
                pdf.cell(self.width(*fam, seg), line_h, seg,
                         new_x=XPos.LMARGIN, new_y=YPos.NEXT)
        pdf.set_text_color(25, 30, 40)
        pdf.set_text_shaping(True)
        pdf.ln(2.6)

    # ---------- figures ----------
    def figure(self, caption, relpath):
        from PIL import Image
        pdf = self.pdf
        full = os.path.join(ROOT, relpath)
        iw, ih = Image.open(full).size
        w = pdf.epw
        h = w * ih / iw
        max_h = pdf.h - pdf.t_margin - pdf.b_margin - 30
        if h > max_h:
            h = max_h
            w = h * iw / ih
        cap_base = ("Hind", "", 9)
        cap_lines = self.wrap(caption, cap_base, pdf.epw - 2)
        cap_h = len(cap_lines) * 5.2 + 4
        if pdf.get_y() + h + cap_h > pdf.h - pdf.b_margin:
            pdf.add_page()
        x = pdf.l_margin + (pdf.epw - w) / 2
        y = pdf.get_y()
        pdf.image(full, x=x, y=y, w=w, h=h)
        pdf.set_draw_color(205, 212, 222)
        pdf.rect(x, y, w, h, style="D")
        pdf.set_y(y + h + 2)
        for line_words in cap_lines:
            self.draw_line(line_words, cap_base, 5.2, pdf.l_margin + 1,
                           colors={"": (74, 84, 104), "B": (74, 84, 104),
                                   "code": (74, 84, 104)})
        pdf.ln(3.4)


def render_toc(pdf, outline):
    r = Renderer(pdf)
    pdf.set_text_shaping(True)
    pdf.set_font("Hind", "B", 19)
    pdf.set_text_color(13, 53, 87)
    pdf.cell(0, 10, "সূচিপত্র", new_x=XPos.LMARGIN, new_y=YPos.NEXT)
    pdf.set_draw_color(14, 107, 110)
    pdf.line(pdf.l_margin, pdf.get_y(), pdf.w - pdf.r_margin, pdf.get_y())
    pdf.ln(3.5)
    for entry in outline:
        level = entry.level
        if pdf.get_y() > pdf.h - pdf.b_margin - 8:
            pdf.add_page()
        y = pdf.get_y()
        indent = 0 if level == 0 else 8
        size = 10.8 if level == 0 else 9.4
        style = "B" if level == 0 else ""
        pdf.set_font("Hind", style, size)
        pdf.set_text_color(13, 53, 87) if level == 0 else pdf.set_text_color(45, 55, 70)
        name = sanitize(entry.name)
        page_str = bn_num(entry.page_number)
        pw = pdf.get_string_width(page_str)
        # truncate name if required
        avail = pdf.epw - indent - pw - 8
        while pdf.get_string_width(name) > avail and len(name) > 4:
            name = name[:-2]
        name_w = pdf.get_string_width(name)
        pdf.set_xy(pdf.l_margin + indent, y)
        pdf.cell(name_w, 5.9, name, new_x=XPos.RIGHT, new_y=YPos.TOP)
        # dot leader
        dots_x = pdf.l_margin + indent + name_w + 2
        dots_end = pdf.w - pdf.r_margin - pw - 2
        pdf.set_font("Hind", "", 8)
        pdf.set_text_color(170, 176, 190)
        if dots_end > dots_x + 4:
            ndots = int((dots_end - dots_x) / pdf.get_string_width("."))
            pdf.set_xy(dots_x, y)
            pdf.cell(dots_end - dots_x, 5.9, "." * max(0, ndots),
                     new_x=XPos.RIGHT, new_y=YPos.TOP)
        pdf.set_font("Hind", style, size)
        pdf.set_text_color(13, 53, 87) if level == 0 else pdf.set_text_color(45, 55, 70)
        pdf.set_xy(pdf.w - pdf.r_margin - pw, y)
        pdf.cell(pw, 5.9, page_str, new_x=XPos.LMARGIN, new_y=YPos.NEXT)
        if level == 0:
            pdf.ln(0.6)
    pdf.set_text_color(25, 30, 40)


def strip_md(text):
    return re.sub(r"[*`]", "", sanitize(text)).strip()


def build():
    md_text = open(MD, encoding="utf-8").read()
    blocks = parse(md_text)

    pdf = BookPDF(orientation="P", unit="mm", format="A4")
    pdf.set_margins(18, 20, 18)
    pdf.set_auto_page_break(auto=True, margin=17)
    pdf.c_margin = 0
    pdf.set_title("প্রম্পট ইনজেকশন আক্রমণ (Prompt Injection Attacks) — বাংলা সংস্করণ")
    pdf.set_subject("Prompt Injection Attacks eBook-এর বাংলা অনুবাদ")
    pdf.set_author("বাংলা অনুবাদ")
    pdf.set_keywords("prompt injection, LLM security, OWASP, jailbreak, Bangla, বাংলা")
    pdf.add_font("Hind", "", os.path.join(FONT_DIR, "HindSiliguri-Regular.ttf"))
    pdf.add_font("Hind", "B", os.path.join(FONT_DIR, "HindSiliguri-Bold.ttf"))
    mono = os.path.join(FONT_DIR, "DejaVuSansMono.ttf")
    mono_b = os.path.join(FONT_DIR, "DejaVuSansMono-Bold.ttf")
    if not os.path.exists(mono):
        mono = "/usr/share/fonts/truetype/dejavu/DejaVuSansMono.ttf"
        mono_b = "/usr/share/fonts/truetype/dejavu/DejaVuSansMono-Bold.ttf"
    pdf.add_font("Mono", "", mono)
    pdf.add_font("Mono", "B", mono_b)
    pdf.set_font("Hind", "", 11)

    r = Renderer(pdf)

    # ---------- cover ----------
    pdf.show_chrome = False
    pdf.add_page()
    pdf.set_fill_color(13, 53, 87)
    pdf.rect(0, 0, pdf.w, pdf.h, style="F")
    pdf.set_fill_color(14, 107, 110)
    pdf.rect(0, 92, pdf.w, 2.2, style="F")
    pdf.set_fill_color(255, 209, 102)
    pdf.rect(0, 205, pdf.w, 2.2, style="F")
    pdf.set_text_shaping(True)
    pdf.set_text_color(255, 209, 102)
    pdf.set_font("Hind", "B", 13)
    pdf.set_xy(18, 48)
    pdf.cell(0, 8, "LLM নিরাপত্তা  •  OWASP LLM01 ও LLM02", new_x=XPos.LMARGIN, new_y=YPos.NEXT, align="C")
    pdf.set_text_color(255, 255, 255)
    pdf.set_font("Hind", "B", 34)
    pdf.set_xy(18, 62)
    pdf.cell(0, 16, "প্রম্পট ইনজেকশন আক্রমণ", new_x=XPos.LMARGIN, new_y=YPos.NEXT, align="C")
    pdf.set_font("Mono", "", 15)
    pdf.set_text_color(191, 227, 255)
    pdf.cell(0, 10, "Prompt Injection Attacks", new_x=XPos.LMARGIN, new_y=YPos.NEXT, align="C")
    pdf.set_xy(18, 106)
    pdf.set_font("Hind", "", 15)
    pdf.set_text_color(237, 245, 255)
    pdf.cell(0, 9, "বাংলা সংস্করণ — পূর্ণাঙ্গ অনুবাদ", new_x=XPos.LMARGIN, new_y=YPos.NEXT, align="C")
    pdf.set_font("Hind", "", 11.5)
    pdf.set_xy(18, 124)
    pdf.cell(0, 8, "প্রম্পট ইঞ্জিনিয়ারিং থেকে জেইলব্রেক ও প্রতিরক্ষা পর্যন্ত", new_x=XPos.LMARGIN, new_y=YPos.NEXT, align="C")
    pdf.set_font("Hind", "", 11)
    pdf.set_text_color(255, 209, 102)
    pdf.set_xy(18, 142)
    pdf.cell(0, 8, "১০টি অধ্যায়  •  ৩১টি চিত্র  •  ল্যাব নির্দেশনা ও টুলসহ", new_x=XPos.LMARGIN, new_y=YPos.NEXT, align="C")
    pdf.set_font("Hind", "", 10)
    pdf.set_text_color(210, 225, 240)
    # NOTE: cell() per pre-wrapped line (multi_cell breaks Bengali vowel signs)
    pdf.set_xy(18, 216)
    pdf.cell(0, 6.5, "শিক্ষামূলক সংস্করণ: বইয়ের কৌশলগুলো শুধুমাত্র নিজের সিস্টেম, ল্যাব পরিবেশ",
             new_x=XPos.LMARGIN, new_y=YPos.NEXT, align="C")
    pdf.set_x(18)
    pdf.cell(0, 6.5, "অথবা স্পষ্ট অনুমতি আছে এমন জায়গায় পরীক্ষা করুন।",
             new_x=XPos.LMARGIN, new_y=YPos.NEXT, align="C")
    pdf.set_text_color(25, 30, 40)

    # ---------- TOC placeholder (2 pages, no header/footer chrome chosen) ----------
    pdf.add_page()  # TOC starts here
    pdf.set_y(20)
    pdf.insert_toc_placeholder(render_toc, pages=1, allow_extra_pages=False)
    pdf.show_chrome = True

    # ---------- body ----------
    first_h1 = True
    pending_intro = True
    body_base = ("Hind", "", Renderer.BODY_SIZE)
    for kind, data in blocks:
        if kind == "h1":
            text = data
            if first_h1:
                first_h1 = False
                pdf.start_section("ভূমিকা", level=0)
                pdf.set_text_color(13, 53, 87)
                r.paragraph("ভূমিকা", ("Hind", "B", 20), 11, space_after=3)
                pdf.set_draw_color(14, 107, 110)
                y = pdf.get_y()
                pdf.line(pdf.l_margin, y, pdf.w - pdf.r_margin, y)
                pdf.ln(4)
                continue
            pdf.add_page()
            pdf.start_section(strip_md(text), level=0)
            r.paragraph(strip_md(text), ("Hind", "B", 19), 10.5, space_after=3,
                        colors={"": (13, 53, 87), "B": (13, 53, 87)})
            pdf.set_draw_color(14, 107, 110)
            y = pdf.get_y()
            pdf.line(pdf.l_margin, y, pdf.w - pdf.r_margin, y)
            pdf.ln(4)
        elif kind == "h2":
            if pending_intro:
                # the english subtitle h2 right after the title: skip once
                pending_intro = False
                continue
            pdf.start_section(strip_md(data), level=1)
            pdf.ln(2)
            r.paragraph(data, ("Hind", "B", 14.5), 8.6, space_after=2.4,
                        colors={"": (18, 69, 110), "B": (18, 69, 110), "code": (18, 69, 110)})
        elif kind == "h3":
            pdf.ln(1.4)
            r.paragraph(data, ("Hind", "B", 12.5), 7.6, space_after=2,
                        colors={"": (14, 107, 110), "B": (14, 107, 110), "code": (14, 107, 110)})
        elif kind == "p":
            r.paragraph(data, body_base, Renderer.BODY_H, space_after=3.0)
        elif kind == "ul":
            for item in data:
                r.paragraph(item, body_base, Renderer.BODY_H, indent=4,
                            space_after=1.4, first_prefix="•  ")
            pdf.ln(2)
        elif kind == "code":
            r.code_block(data)
        elif kind == "img":
            caption, path = data
            r.figure(caption, path)
        elif kind == "quote":
            pdf.ln(1.2)
            r.paragraph(data, ("Hind", "", 10), 5.9, indent=3.5,
                        bg=(255, 243, 205), space_after=4.5)
        elif kind == "hr":
            r.ensure_space(6)
            y = pdf.get_y() + 2
            pdf.set_draw_color(200, 205, 215)
            pdf.line(pdf.l_margin, y, pdf.w - pdf.r_margin, y)
            pdf.set_y(y + 4)

    pdf.output(OUT)
    print("PDF written:", OUT, os.path.getsize(OUT), "bytes, pages:", pdf.page)


if __name__ == "__main__":
    build()
