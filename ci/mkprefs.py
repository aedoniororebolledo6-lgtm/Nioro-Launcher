#!/usr/bin/env python3
"""Crea los ajustes del launcher para las capturas: apps en el inicio y el dock y el estilo de vista de apps.
Uso: mkprefs.py acts.txt ESTILO salida.xml"""
import sys
from xml.sax.saxutils import escape

acts, style, out = sys.argv[1], int(sys.argv[2]), sys.argv[3]
keys = []
for line in open(acts, encoding="utf-8", errors="ignore"):
    line = line.strip()
    if "/" not in line or " " in line:
        continue
    pkg, cls = line.split("/", 1)
    if cls.startswith("."):
        cls = pkg + cls
    k = pkg + "/" + cls
    if pkg != "com.nioro.launcher" and k not in keys:
        keys.append(k)
dock = keys[:4]
home = keys[4:16]
nl = "&#10;"
xml = ["<?xml version='1.0' encoding='utf-8' standalone='yes' ?>", "<map>",
       '    <boolean name="seeded" value="true" />',
       '    <int name="drawerStyle" value="%d" />' % style,
       '    <string name="dockKeys">%s</string>' % nl.join(escape(k) for k in dock),
       '    <string name="homeKeys">%s</string>' % nl.join(escape(k) for k in home),
       '    <string name="knownKeys">%s</string>' % nl.join(escape(k) for k in keys),
       "</map>", ""]
open(out, "w", encoding="utf-8").write("\n".join(xml))
print("apps:", len(keys), "inicio:", len(home), "dock:", len(dock))
