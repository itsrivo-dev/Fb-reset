#!/usr/bin/env python3
"""Assemble work/book.md from the translated chapter parts in work/src/.

Also refreshes the deliverable Markdown copy next to the PDF/HTML outputs.
"""
import os
import shutil

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.abspath(os.path.join(HERE, ".."))
SRC = os.path.join(HERE, "src")

ORDER = [
    "part00_front.md",
    "part01_ch1.md",
    "part02_ch2.md",
    "part03_ch3a.md",
    "part04_ch3b.md",
    "part05_ch4.md",
    "part06_ch5.md",
    "part07_ch6.md",
    "part08_ch7.md",
    "part09_ch8.md",
    "part10_ch9_setup_glossary.md",
]


def main():
    parts = [
        open(os.path.join(SRC, name), encoding="utf-8").read().strip()
        for name in ORDER
    ]
    text = "\n\n".join(parts)
    dan = open(os.path.join(SRC, "dan_block.txt"), encoding="utf-8").read().strip()
    assert "{{DAN_CODE_BLOCK}}" in text, "DAN placeholder missing from chapter 6"
    text = text.replace("{{DAN_CODE_BLOCK}}", "```\n" + dan + "\n```")
    book_md = os.path.join(HERE, "book.md")
    with open(book_md, "w", encoding="utf-8") as fh:
        fh.write(text + "\n")
    shutil.copy(book_md, os.path.join(ROOT, "Prompt-Injection-Attacks-Bangla.md"))
    print(f"book.md assembled: {len(text)} chars")


if __name__ == "__main__":
    main()
