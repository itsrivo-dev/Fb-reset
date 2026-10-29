#!/usr/bin/env python3
# Build standalone one-file HTML ebook from book.md (images embedded as base64)
import base64
import html as htmlmod
import os
import re

ROOT = os.path.abspath(os.path.join(os.path.dirname(os.path.abspath(__file__)), ".."))
MD = os.path.join(ROOT, "work", "book.md")
OUT = os.path.join(ROOT, "Prompt-Injection-Attacks-Bangla.html")

img_cache = {}


def embed_image(relpath):
    if relpath in img_cache:
        return img_cache[relpath]
    full = os.path.join(ROOT, relpath)
    with open(full, "rb") as fh:
        data = base64.b64encode(fh.read()).decode("ascii")
    uri = f"data:image/png;base64,{data}"
    img_cache[relpath] = uri
    return uri


def inline(text):
    """Inline markdown: `code`, **bold** after HTML-escaping."""
    text = htmlmod.escape(text)
    # code first, protect its contents from bold processing by placeholder
    parts = re.split(r"(`[^`]+`)", text)
    out = []
    for part in parts:
        if part.startswith("`") and part.endswith("`") and len(part) > 2:
            out.append(f"<code>{part[1:-1]}</code>")
        else:
            part = re.sub(r"\*\*([^*]+)\*\*", r"<strong>\1</strong>", part)
            out.append(part)
    return "".join(out)


def parse(md_text):
    lines = md_text.splitlines()
    blocks = []  # (kind, data)
    i = 0
    para = []

    def flush_para():
        if para:
            blocks.append(("p", " ".join(x.strip() for x in para).strip()))
            para.clear()

    while i < len(lines):
        line = lines[i]
        stripped = line.strip()
        fence = re.match(r"^(`{3,})", stripped)
        if fence:
            flush_para()
            fence_len = len(fence.group(1))
            code = []
            i += 1
            while i < len(lines):
                m = re.match(r"^(`{3,})\s*$", lines[i].strip())
                if m and len(m.group(1)) >= fence_len:
                    break
                code.append(lines[i])
                i += 1
            blocks.append(("code", "\n".join(code)))
            i += 1
            continue
        if not stripped:
            flush_para()
            i += 1
            continue
        if stripped == "---":
            flush_para()
            blocks.append(("hr", ""))
            i += 1
            continue
        m_img = re.match(r"^!\[(.*?)\]\((.*?)\)\s*$", stripped)
        if m_img:
            flush_para()
            blocks.append(("img", (m_img.group(1), m_img.group(2))))
            i += 1
            continue
        m_h = re.match(r"^(#{1,3})\s+(.*)$", stripped)
        if m_h:
            flush_para()
            blocks.append((f"h{len(m_h.group(1))}", m_h.group(2).strip()))
            i += 1
            continue
        if stripped.startswith("> "):
            flush_para()
            quote = [stripped[2:]]
            i += 1
            while i < len(lines) and lines[i].strip().startswith("> "):
                quote.append(lines[i].strip()[2:])
                i += 1
            blocks.append(("quote", " ".join(quote)))
            continue
        if re.match(r"^[-*]\s+", stripped):
            flush_para()
            items = []
            while i < len(lines) and re.match(r"^[-*]\s+", lines[i].strip()):
                items.append(re.sub(r"^[-*]\s+", "", lines[i].strip()))
                i += 1
            blocks.append(("ul", items))
            continue
        para.append(line)
        i += 1
    flush_para()
    return blocks


def main():
    md_text = open(MD, encoding="utf-8").read()
    blocks = parse(md_text)

    # assign ids to headings, build TOC from h1/h2
    toc = []
    rendered = []
    sec = 0
    first_h1_done = False
    for kind, data in blocks:
        if kind in ("h1", "h2"):
            sec += 1
            hid = f"sec-{sec}"
            toc.append((kind, hid, data))
            if kind == "h1" and not first_h1_done:
                first_h1_done = True
                rendered.append(("hero", (hid, data)))
            else:
                rendered.append((kind, (hid, data)))
        else:
            rendered.append((kind, data))

    toc_html = ['<nav class="toc"><h2>সূচিপত্র</h2><ol>']
    for kind, hid, text in toc:
        plain = re.sub(r"[*`]", "", text)
        cls = "toc-h1" if kind == "h1" else "toc-h2"
        toc_html.append(
            f'<li class="{cls}"><a href="#{hid}">{htmlmod.escape(plain)}</a></li>'
        )
    toc_html.append("</ol></nav>")

    body = []
    toc_inserted = False
    for kind, data in rendered:
        if kind == "hero":
            hid, text = data
            body.append(
                f'<header class="hero" id="{hid}"><div class="hero-badge">বাংলা অনুবাদ • Bangla Edition</div>'
                f"<h1>{inline(text)}</h1>"
            )
            # hero continues; subtitle h2 and meta flow after, closed later by first hr handling below
            continue
        if kind == "h1":
            hid, text = data
            if not toc_inserted:
                body.append("</header>" + "".join(toc_html) + "<main>")
                toc_inserted = True
            body.append(f'<h1 class="chapter" id="{hid}">{inline(text)}</h1>')
        elif kind == "h2":
            hid, text = data
            body.append(f'<h2 id="{hid}">{inline(text)}</h2>')
        elif kind == "h3":
            body.append(f"<h3>{inline(data)}</h3>")
        elif kind == "p":
            body.append(f"<p>{inline(data)}</p>")
        elif kind == "ul":
            items = "".join(f"<li>{inline(x)}</li>" for x in data)
            body.append(f"<ul>{items}</ul>")
        elif kind == "code":
            body.append(
                f'<pre dir="ltr"><code>{htmlmod.escape(data)}</code></pre>'
            )
        elif kind == "img":
            caption, path = data
            imguri = embed_image(path)
            body.append(
                f'<figure><img loading="lazy" src="{imguri}" alt="{htmlmod.escape(caption)}">'
                f"<figcaption>{inline(caption)}</figcaption></figure>"
            )
        elif kind == "quote":
            body.append(f'<aside class="note">{inline(data)}</aside>')
        elif kind == "hr":
            body.append("<hr>")
    if toc_inserted:
        body.append("</main>")
    else:
        body.append("</header>")

    css = """
*{box-sizing:border-box}
html{scroll-behavior:smooth}
body{margin:0;background:#f4f1ea;color:#1f2430;
 font-family:'Hind Siliguri','Noto Sans Bengali','SolaimanLipi',sans-serif;
 line-height:1.85;font-size:18px}
.hero{background:linear-gradient(135deg,#0d2137,#12456e 55%,#0e6b6e);color:#fff;
 padding:72px 22px 44px;text-align:center}
.hero h1{font-size:clamp(34px,6vw,56px);line-height:1.3;margin:14px auto 6px;max-width:900px}
.hero h2{color:#bfe3ff;font-size:clamp(19px,3vw,26px);border:none;margin:4px 0 18px}
.hero h3{color:#e8f4ff}
.hero p,.hero li{text-align:left;max-width:820px;margin-left:auto;margin-right:auto;color:#edf5ff}
.hero .note{background:rgba(255,255,255,.12);border-color:#ffd166;color:#fff;max-width:820px;margin:16px auto;text-align:left}
.hero a{color:#ffd166}
.hero-badge{display:inline-block;background:#ffd166;color:#17324d;font-weight:700;
 padding:4px 16px;border-radius:999px;font-size:14px;letter-spacing:.4px}
nav.toc{max-width:860px;margin:34px auto;background:#fff;border:1px solid #ddd4c2;
 border-radius:14px;padding:22px 30px;box-shadow:0 4px 18px rgba(30,40,60,.06)}
nav.toc h2{margin:0 0 8px;border:none;color:#12456e}
nav.toc ol{list-style:none;padding:0;margin:0}
nav.toc li{padding:3px 0}
nav.toc .toc-h1{font-weight:700;margin-top:9px}
nav.toc .toc-h2{padding-left:24px;font-size:16px}
nav.toc a{color:#17324d;text-decoration:none}
nav.toc a:hover{color:#0e6b6e;text-decoration:underline}
main{max-width:860px;margin:0 auto 70px;background:#fff;border:1px solid #e3dccb;
 border-radius:14px;padding:38px clamp(18px,5vw,54px) 54px;box-shadow:0 4px 18px rgba(30,40,60,.06)}
h1.chapter{font-size:30px;color:#0d3557;border-bottom:3px solid #0e6b6e;
 padding-bottom:8px;margin:46px 0 16px;line-height:1.5}
h2{font-size:23px;color:#12456e;margin:32px 0 10px;line-height:1.55}
h3{font-size: extraneous;font-size:20px;color:#0e6b6e;margin:26px 0 8px;line-height:1.55}
p{margin:10px 0;text-align:justify}
ul{margin:10px 0 14px;padding-left:26px}
li{margin:5px 0}
code{font-family:'DejaVu Sans Mono',Consolas,monospace;font-size:.86em;direction:ltr;
 unicode-bidi:isolate;background:#eef3e8;color:#8a2d1c;padding:1px 6px;border-radius:5px}
pre{background:#14202e;color:#d9f2dc;border-radius:10px;padding:16px 18px;overflow:auto;
 line-height:1.6;margin:14px 0}
pre code{background:none;color:inherit;padding:0;font-size:14.5px;white-space:pre;}
figure{margin:22px 0;background:#faf8f2;border:1px solid #e2dac9;border-radius:12px;
 padding:12px}
figure img{width:100%;height:auto;display:block;border-radius:8px}
figcaption{font-size:15px;color:#4a5468;margin-top:10px;line-height:1.75}
.note{background:#fff7dd;border-left:5px solid #e5a900;border-radius:8px;
 padding:12px 18px;margin:16px 0;font-size:16.5px}
hr{border:none;border-top:1px solid #d8d0bd;margin:28px 0}
footer{max-width:860px;margin:0 auto 50px;text-align:center;color:#6a7284;font-size:15px;padding:0 18px}
@media (max-width:640px){body{font-size:16.5px} main{margin:0 8px 40px} nav.toc{margin:20px 8px}}
"""

    doc = f"""<!DOCTYPE html>
<html lang="bn">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>প্রম্পট ইনজেকশন আক্রমণ — Prompt Injection Attacks (বাংলা সংস্করণ)</title>
<meta name="description" content="Prompt Injection Attacks eBook-এর পূর্ণাঙ্গ বাংলা অনুবাদ: প্রম্পট ইঞ্জিনিয়ারিং, ডাইরেক্ট ও ইনডাইরেক্ট প্রম্পট ইনজেকশন, জেইলব্রেক, garak এবং প্রতিরক্ষা কৌশল।">
<link rel="preconnect" href="https://fonts.googleapis.com">
<link href="https://fonts.googleapis.com/css2?family=Hind+Siliguri:wght@400;600;700&family=Noto+Sans+Bengali:wght@400;600;700&display=swap" rel="stylesheet">
<style>{css}</style>
</head>
<body>
{''.join(body)}
<footer>প্রম্পট ইনজেকশন আক্রমণ — Prompt Injection Attacks-এর বাংলা সংস্করণ। শিক্ষামূলক উদ্দেশ্যে; কৌশলগুলো শুধুমাত্র অনুমতিপ্রাপ্ত ল্যাব/সিস্টেমে পরীক্ষা করুন।</footer>
</body>
</html>
"""
    # fix stray token in CSS if present
    doc = doc.replace("font-size: extraneous;", "")
    with open(OUT, "w", encoding="utf-8") as fh:
        fh.write(doc)
    print("HTML written:", OUT, len(doc), "bytes, images embedded:", len(img_cache))


if __name__ == "__main__":
    main()
