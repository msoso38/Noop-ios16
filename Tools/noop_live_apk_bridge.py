#!/usr/bin/env python3
"""Local browser review bridge for the running NOOP Android APK.

The bridge mirrors a real ADB device and anchors review notes to live accessibility
nodes. It is intentionally not a web reimplementation: source edits remain Compose
changes made after reviewing real annotated frames.

Closed loop: notes carry a status (pending -> queued -> in_progress -> applied/declined)
and an agent reply. The browser "Apply with AI" button flips pending notes to queued.
An agent reads the queue (`GET /api/queue` or the notes file directly), edits Compose,
then reports back with `POST /api/note/status`. The server is the single writer for the
notes file so the browser and the agent never race.
"""

from __future__ import annotations

import argparse
import json
import secrets
import subprocess
import sys
import threading
import time
import xml.etree.ElementTree as element_tree
from http import HTTPStatus
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from urllib.parse import urlparse

ROOT = Path(__file__).resolve().parents[1]
NOTES = ROOT / ".impeccable" / "native-review" / "notes.jsonl"
ADB = Path.home() / "AppData" / "Local" / "Android" / "Sdk" / "platform-tools" / "adb.exe"
NOTES_LOCK = threading.Lock()

STATUSES = ("pending", "queued", "in_progress", "applied", "declined")


def devices() -> list[str]:
    result = subprocess.run([str(ADB), "devices"], capture_output=True, text=True, timeout=10, check=False)
    return [line.split()[0] for line in result.stdout.splitlines()[1:] if line.endswith("\tdevice")]


def bounds(raw: str) -> tuple[int, int, int, int] | None:
    try:
        left, top, right, bottom = (int(value) for value in raw.replace("][", ",").replace("[", "").replace("]", "").split(","))
        return left, top, right, bottom
    except ValueError:
        return None


def note_rows() -> list[dict]:
    if not NOTES.exists():
        return []
    rows: list[dict] = []
    for line_number, line in enumerate(NOTES.read_text(encoding="utf-8", errors="replace").splitlines()):
        if not line.strip():
            continue
        try:
            row = json.loads(line)
        except json.JSONDecodeError:
            continue
        row.setdefault("id", f"legacy-{line_number}")
        row.setdefault("status", "pending")
        rows.append(row)
    return rows


def write_note_rows(rows: list[dict]) -> None:
    NOTES.parent.mkdir(parents=True, exist_ok=True)
    NOTES.write_text("".join(json.dumps(row, separators=(",", ":")) + "\n" for row in rows), encoding="utf-8")


class Bridge:
    def __init__(self, serial: str) -> None:
        self.serial = serial
        self.layout_lock = threading.Lock()

    def adb(self, *args: str, binary: bool = False) -> bytes:
        result = subprocess.run(
            [str(ADB), "-s", self.serial, *args],
            capture_output=True,
            timeout=15,
            check=False,
        )
        if result.returncode != 0:
            raise RuntimeError(result.stderr.decode("utf-8", "replace").strip() or "ADB command failed")
        return result.stdout if binary else result.stdout.strip()

    def frame(self) -> bytes:
        png = self.adb("exec-out", "screencap", "-p", binary=True)
        # Android 16 can prepend a multi-display diagnostic to screencap stdout.
        png_start = png.find(b"\x89PNG\r\n\x1a\n")
        if png_start < 0:
            raise RuntimeError("Device did not return a PNG frame")
        return png[png_start:]

    def layout(self) -> list[dict]:
        with self.layout_lock:
            self.adb("shell", "uiautomator", "dump", "/sdcard/noop-live-review.xml")
            raw_xml = self.adb("exec-out", "cat", "/sdcard/noop-live-review.xml", binary=True)
        xml_start = raw_xml.find(b"<?xml")
        if xml_start < 0:
            raise RuntimeError("Device did not return an accessibility hierarchy")
        root = element_tree.fromstring(raw_xml[xml_start:].decode("utf-8", "replace"))
        nodes: list[dict] = []

        def visit(element: element_tree.Element, path: str) -> None:
            node_bounds = bounds(element.get("bounds", ""))
            if node_bounds:
                left, top, right, bottom = node_bounds
                if right > left and bottom > top:
                    nodes.append(
                        {
                            "path": path,
                            "bounds": node_bounds,
                            "text": element.get("text", ""),
                            "description": element.get("content-desc", ""),
                            "resource_id": element.get("resource-id", ""),
                            "class_name": element.get("class", ""),
                            "clickable": element.get("clickable") == "true",
                        }
                    )
            for index, child in enumerate(element):
                visit(child, f"{path}.{index}")

        visit(root, "0")
        return nodes

    def state(self) -> dict:
        with NOTES_LOCK:
            mine = [row for row in note_rows() if row.get("serial") == self.serial]
        counts = {status: sum(1 for row in mine if row.get("status", "pending") == status) for status in STATUSES}
        return {
            "serial": self.serial,
            "connected": self.serial in devices(),
            "notes": len(mine),
            "counts": counts,
        }

    def tap(self, x: int, y: int) -> None:
        self.adb("shell", "input", "tap", str(x), str(y))

    def swipe(self, x1: int, y1: int, x2: int, y2: int, duration: int) -> None:
        self.adb("shell", "input", "swipe", str(x1), str(y1), str(x2), str(y2), str(duration))

    def key(self, keycode: str) -> None:
        self.adb("shell", "input", "keyevent", keycode)


def contains(node: dict, x: int, y: int) -> bool:
    left, top, right, bottom = node["bounds"]
    return left <= x <= right and top <= y <= bottom


def node_area(node: dict) -> int:
    left, top, right, bottom = node["bounds"]
    return (right - left) * (bottom - top)


def anchor_at(nodes: list[dict], x: int, y: int) -> dict | None:
    candidates = [node for node in nodes if contains(node, x, y)]
    if not candidates:
        return None
    # The smallest node moves with the visual element. Viewport-sized Compose containers
    # stay fixed while their contents scroll, so they must never become note anchors.
    candidates.sort(key=node_area)
    node = candidates[0]
    left, top, right, bottom = node["bounds"]
    return {
        "path": node["path"],
        "description": node["description"],
        "text": node["text"],
        "resource_id": node["resource_id"],
        "class_name": node["class_name"],
        "relative_x": round((x - left) / (right - left), 4),
        "relative_y": round((y - top) / (bottom - top), 4),
    }


def matching_node(nodes: list[dict], anchor: dict) -> dict | None:
    for field in ("description", "resource_id", "text"):
        value = anchor.get(field, "")
        if value:
            matches = [node for node in nodes if node.get(field) == value]
            if len(matches) == 1:
                return matches[0]
            path_match = next((node for node in matches if node["path"] == anchor.get("path")), None)
            if path_match:
                return path_match
    return next((node for node in nodes if node["path"] == anchor.get("path")), None)


def projected_note(row: dict, nodes: list[dict]) -> dict:
    review = {key: value for key, value in row.items() if key not in {"anchor"}}
    review.setdefault("status", "pending")
    anchor = row.get("anchor")
    if isinstance(anchor, dict):
        review["anchor"] = anchor
    node = matching_node(nodes, anchor) if isinstance(anchor, dict) else None
    if node:
        left, top, right, bottom = node["bounds"]
        review["position"] = {
            "x": round(left + (right - left) * float(anchor.get("relative_x", 0.5))),
            "y": round(top + (bottom - top) * float(anchor.get("relative_y", 0.5))),
            "anchored": True,
        }
    elif anchor:
        review["position"] = None
        review["off_screen"] = True
    else:
        review["position"] = {"x": row.get("x", 0), "y": row.get("y", 0), "anchored": False}
        review["legacy"] = True
    return review


def is_viewport_anchor(nodes: list[dict], anchor: dict) -> bool:
    node = matching_node(nodes, anchor)
    if not node or not nodes:
        return False
    return node_area(node) >= max(node_area(candidate) for candidate in nodes) * 0.6


def review_notes(bridge: Bridge) -> dict:
    nodes = bridge.layout()
    changed = False
    with NOTES_LOCK:
        rows = note_rows()
        # Upgrade coordinate-only notes while their original element is still visible.
        for row in rows:
            if row.get("serial") == bridge.serial and (
                not row.get("anchor") or is_viewport_anchor(nodes, row["anchor"])
            ):
                anchor = anchor_at(nodes, int(row.get("x", 0)), int(row.get("y", 0)))
                if anchor:
                    row["anchor"] = anchor
                    changed = True
        if changed:
            write_note_rows(rows)
        reviews = [projected_note(row, nodes) for row in rows if row.get("serial") == bridge.serial]
    return {"notes": reviews}


def add_note(payload: dict, bridge: Bridge) -> dict:
    text = str(payload.get("text") or "").strip()
    if not text:
        raise ValueError("A note needs text")
    x, y = int(payload.get("x", 0)), int(payload.get("y", 0))
    row = {
        "id": secrets.token_hex(6),
        "ts_ms": int(time.time() * 1000),
        "serial": bridge.serial,
        "status": "pending",
        "x": x,
        "y": y,
        "text": text,
        "anchor": anchor_at(bridge.layout(), x, y),
    }
    with NOTES_LOCK:
        rows = note_rows()
        rows.append(row)
        write_note_rows(rows)
    return row


def edit_note(payload: dict, bridge: Bridge, delete: bool = False) -> None:
    note_id = str(payload.get("id") or "")
    if not note_id:
        raise ValueError("Choose a note first")
    text = str(payload.get("text") or "").strip()
    if not delete and not text:
        raise ValueError("A note needs text")
    with NOTES_LOCK:
        rows = note_rows()
        found = False
        updated: list[dict] = []
        for row in rows:
            if row.get("id") == note_id and row.get("serial") == bridge.serial:
                found = True
                if delete:
                    continue
                row["text"] = text
                row["updated_ms"] = int(time.time() * 1000)
                # Editing the ask reopens it for the agent.
                if row.get("status") in ("applied", "declined"):
                    row["status"] = "pending"
                    row.pop("agent_reply", None)
            updated.append(row)
        if not found:
            raise ValueError("The selected note no longer exists")
        write_note_rows(updated)


def queue_pending(bridge: Bridge) -> int:
    """Move every pending note on this device into the agent queue."""
    now = int(time.time() * 1000)
    queued = 0
    with NOTES_LOCK:
        rows = note_rows()
        for row in rows:
            if row.get("serial") == bridge.serial and row.get("status", "pending") == "pending":
                row["status"] = "queued"
                row["queued_ms"] = now
                queued += 1
        if queued:
            write_note_rows(rows)
    return queued


def agent_queue(bridge: Bridge) -> dict:
    """Notes the agent still owes work on. Uses the stored anchor, no live layout,
    so it stays fast and works even when the element has scrolled off screen."""
    with NOTES_LOCK:
        rows = note_rows()
        pending_work = [
            {
                "id": row.get("id"),
                "status": row.get("status"),
                "text": row.get("text", ""),
                "anchor": row.get("anchor"),
                "x": row.get("x", 0),
                "y": row.get("y", 0),
                "ts_ms": row.get("ts_ms"),
                "queued_ms": row.get("queued_ms"),
                "agent_reply": row.get("agent_reply", ""),
            }
            for row in rows
            if row.get("serial") == bridge.serial and row.get("status") in ("queued", "in_progress")
        ]
    return {"serial": bridge.serial, "notes": pending_work}


def set_status(payload: dict, bridge: Bridge) -> dict:
    note_id = str(payload.get("id") or "")
    status = str(payload.get("status") or "").strip()
    if not note_id:
        raise ValueError("A note id is required")
    if status not in STATUSES:
        raise ValueError(f"status must be one of {', '.join(STATUSES)}")
    reply = str(payload.get("reply") or "").strip()
    with NOTES_LOCK:
        rows = note_rows()
        found = None
        for row in rows:
            if row.get("id") == note_id and row.get("serial") == bridge.serial:
                row["status"] = status
                row["agent_ms"] = int(time.time() * 1000)
                if reply:
                    row["agent_reply"] = reply
                found = {"id": note_id, "status": status}
        if not found:
            raise ValueError("The selected note no longer exists")
        write_note_rows(rows)
    return found


PAGE = r"""<!doctype html>
<html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>NOOP APK Live Review</title>
<style>
:root{color-scheme:dark;--bg:#0c0d10;--surface:#16181e;--inset:#1c1f26;--ink:#f2f0ea;--muted:#a8a49a;--quiet:#6e6a62;--line:#ffffff22;--accent:#d4a84b;--accent-ink:#1d1708;--danger:#c45c5c;--ok:#6fbf87;--info:#6fa8d6}
*{box-sizing:border-box}
body{margin:0;background:var(--bg);color:var(--ink);font:500 14px/1.45 Roboto,Arial,sans-serif}
.shell{min-height:100vh;display:grid;grid-template-columns:minmax(0,1fr) 392px}
.stage{padding:24px;display:grid;place-items:start center;background:#090a0d}
.device{position:relative;width:min(100%,460px);overflow:hidden;background:#000;border:1px solid var(--line);border-radius:16px}
.device img{display:block;width:100%;height:auto}
.pins{position:absolute;inset:0;pointer-events:none}
.pin{position:absolute;display:grid;place-items:center;width:28px;height:28px;transform:translate(-50%,-50%);border:2px solid var(--accent);border-radius:50%;background:#15120b;color:var(--accent);font:700 12px/1 Roboto,Arial,sans-serif;box-shadow:0 0 0 2px #0008;pointer-events:auto}
.pin.legacy{border-style:dashed;color:var(--muted);border-color:var(--muted)}
.pin.applied{border-color:var(--ok);color:var(--ok);background:#0f1a12}
.pin.declined{border-color:var(--danger);color:var(--danger);background:#1a1010}
.panel{padding:24px;border-left:1px solid var(--line);background:var(--surface)}
h1{margin:0 0 4px;font-size:20px;line-height:1.2}
h2{margin:24px 0 8px;font-size:14px;line-height:1.3}
p{margin:0;color:var(--muted)}
.status{margin:16px 0;padding:10px 12px;border-radius:10px;background:#ffffff0c;color:var(--muted)}
.controls,.mode,.actions{display:grid;gap:8px}
.controls{grid-template-columns:repeat(4,1fr)}
.mode{grid-template-columns:1fr 1fr}
.actions{grid-template-columns:1fr auto}
.controls button,.mode button,.actions button,.note-row button{min-height:44px;border:1px solid var(--line);border-radius:10px;background:var(--inset);color:var(--ink);font:600 14px/1 Roboto,Arial,sans-serif;cursor:pointer}
.mode button.active,.actions button.primary{background:var(--accent);border-color:var(--accent);color:var(--accent-ink)}
.controls button.toggle.on{border-color:var(--ok);color:var(--ok)}
.actions button.danger{color:#ffb4ab}
.apply{margin-top:12px;width:100%;min-height:48px;border:1px solid var(--accent);border-radius:12px;background:var(--accent);color:var(--accent-ink);font:700 15px/1 Roboto,Arial,sans-serif;cursor:pointer}
.apply:disabled{opacity:.4;cursor:default;background:var(--inset);color:var(--muted);border-color:var(--line)}
textarea{width:100%;min-height:92px;resize:vertical;padding:12px;border:1px solid var(--line);border-radius:10px;background:var(--bg);color:var(--ink);font:500 14px/1.45 Roboto,Arial,sans-serif}
.hint{margin-top:8px;font-size:12px;color:var(--muted)}
.note-list{display:grid;gap:8px;margin-top:10px}
.note-row{display:grid;grid-template-columns:28px minmax(0,1fr);gap:10px;align-items:start;padding:10px;border:1px solid var(--line);border-radius:10px;background:#0c0d10}
.note-row.selected{border-color:var(--accent)}
.note-row>button{min-height:28px;width:28px;border-radius:50%;background:transparent;color:var(--accent);border:1px solid var(--line)}
.note-copy{min-width:0}
.badge{display:inline-block;margin-bottom:4px;padding:1px 8px;border-radius:999px;font:700 10px/1.6 Roboto,Arial,sans-serif;text-transform:uppercase;letter-spacing:.04em;border:1px solid var(--line);color:var(--muted)}
.badge.queued{color:var(--accent);border-color:var(--accent)}
.badge.in_progress{color:var(--info);border-color:var(--info)}
.badge.applied{color:var(--ok);border-color:var(--ok)}
.badge.declined{color:var(--danger);border-color:var(--danger)}
.note-copy .anchor{display:block;font-size:11px;color:var(--quiet);overflow:hidden;text-overflow:ellipsis;white-space:nowrap}
.note-copy .body{display:block;color:var(--ink)}
.note-copy .reply{display:block;margin-top:6px;padding:8px;border-left:2px solid var(--info);background:#0e131a;border-radius:0 8px 8px 0;font-size:12px;color:var(--muted)}
.empty{color:var(--quiet);font-size:13px}
.error{color:#ffb4ab}
@media(max-width:820px){.shell{grid-template-columns:1fr}.panel{border-top:1px solid var(--line);border-left:0}.stage{padding:16px}.device{width:min(100%,460px)}}
</style></head><body><main class="shell"><section class="stage"><div class="device" id="device"><img id="frame" alt="Live NOOP APK frame"><div id="pins" class="pins" aria-live="polite"></div></div></section><aside class="panel"><h1>NOOP APK live review</h1><p>Control the physical app, attach a change note to a real Compose element, then hand the batch to the AI.</p><div class="status" id="status">Connecting to device...</div><div class="controls"><button data-key="4">Back</button><button data-key="3">Home</button><button id="refresh">Refresh</button><button id="live" class="toggle">Live: off</button></div><h2>Mode</h2><div class="mode"><button id="touch" class="active">Control app</button><button id="note">Annotate element</button></div><h2 id="editor-title">New change note</h2><textarea id="text" placeholder="Describe the exact change, then click the element in the APK."></textarea><div class="actions"><button id="save" class="primary">Place note</button><button id="cancel" hidden>New</button></div><p class="hint" id="hint">Markers follow their element when you scroll. If an element is outside the live frame, its note stays in the list as off-screen.</p><button id="apply" class="apply" disabled>Apply with AI</button><p class="hint">"Apply with AI" hands every pending note to the agent. The agent edits Compose, then each note flips to Applied or Declined with a reply below it.</p><h2>Review notes</h2><div id="note-list" class="note-list"><p class="empty">No notes on this device.</p></div></aside></main><script>
const frame=document.querySelector('#frame'),device=document.querySelector('#device'),pins=document.querySelector('#pins'),status=document.querySelector('#status'),text=document.querySelector('#text'),noteList=document.querySelector('#note-list'),save=document.querySelector('#save'),cancel=document.querySelector('#cancel'),title=document.querySelector('#editor-title'),hint=document.querySelector('#hint'),applyBtn=document.querySelector('#apply'),liveBtn=document.querySelector('#live');
let mode='touch',start=null,notes=[],selected=null,live=false,busy=false,inflight=false;
const STATUS_LABEL={pending:'Pending',queued:'Queued for AI',in_progress:'AI working',applied:'Applied',declined:'Declined'};
async function api(path,body){const response=await fetch(path,{method:body?'POST':'GET',headers:body?{'Content-Type':'application/json'}:{},body:body?JSON.stringify(body):undefined});if(!response.ok)throw new Error((await response.text()).replace(/^.*"error"\s*:\s*"?([^"}]+).*$/,'$1'));return response.headers.get('content-type')?.includes('json')?response.json():response}
function setMode(next){mode=next;document.querySelector('#touch').classList.toggle('active',next==='touch');document.querySelector('#note').classList.toggle('active',next==='note');hint.textContent=next==='note'?'Write a change, then click its element in the live APK. The saved marker will follow that element as it scrolls.':'Drag to scroll the real APK, or tap to interact. Refresh updates the live frame and anchored marker positions.'}
function resetEditor(){selected=null;text.value='';title.textContent='New change note';save.textContent='Place note';cancel.hidden=true;setMode('note')}
function selectNote(note){selected=note.id;text.value=note.text;title.textContent='Edit change note';save.textContent='Save note';cancel.hidden=false;setMode('note');renderNotes()}
function nativePoint(event){const rect=device.getBoundingClientRect(),scaleX=frame.naturalWidth/rect.width,scaleY=frame.naturalHeight/rect.height;return{x:Math.round((event.clientX-rect.left)*scaleX),y:Math.round((event.clientY-rect.top)*scaleY),cssX:event.clientX-rect.left,cssY:event.clientY-rect.top}}
function anchorLabel(note){const a=note.anchor;if(note.off_screen)return'Off-screen element';if(!a)return note.legacy?'Screen position (re-anchor by adding a new note)':'Screen position';const parts=[];if(a.resource_id)parts.push(a.resource_id.split('/').pop());if(a.text)parts.push('"'+a.text+'"');else if(a.description)parts.push(a.description);if(a.class_name)parts.push(a.class_name.split('.').pop());return parts.join(' · ')||'Anchored element'}
function renderNotes(){pins.replaceChildren();noteList.replaceChildren();const pending=notes.filter(n=>(n.status||'pending')==='pending').length;applyBtn.disabled=pending===0;applyBtn.textContent=pending?('Apply with AI ('+pending+')'):'Apply with AI';if(!notes.length){noteList.innerHTML='<p class="empty">No notes on this device.</p>';return}for(let index=0;index<notes.length;index++){const note=notes[index],number=index+1,st=note.status||'pending',row=document.createElement('article'),open=document.createElement('button'),copy=document.createElement('div'),badge=document.createElement('span'),anchor=document.createElement('span'),body=document.createElement('span');row.className='note-row'+(selected===note.id?' selected':'');open.textContent=number;open.title='Edit note '+number;open.onclick=()=>selectNote(note);badge.className='badge '+st;badge.textContent=STATUS_LABEL[st]||st;anchor.className='anchor';anchor.textContent=anchorLabel(note);body.className='body';body.textContent=note.text;copy.className='note-copy';copy.append(badge,anchor,body);if(note.agent_reply){const reply=document.createElement('span');reply.className='reply';reply.textContent=note.agent_reply;copy.append(reply)}row.append(open,copy);noteList.append(row);if(!note.position||!frame.naturalWidth||!frame.naturalHeight)continue;const pin=document.createElement('button'),rect=device.getBoundingClientRect();pin.className='pin'+(note.position.anchored?'':' legacy')+(st==='applied'?' applied':st==='declined'?' declined':'');pin.textContent=number;pin.title=note.text;pin.style.left=(note.position.x/frame.naturalWidth*rect.width)+'px';pin.style.top=(note.position.y/frame.naturalHeight*rect.height)+'px';pin.onclick=()=>selectNote(note);pins.append(pin)}}
async function refresh(reloadFrame=true,silent=false){if(inflight)return;inflight=true;try{const state=await api('/api/state');const c=state.counts||{};const bits=[c.applied?c.applied+' applied':null,c.declined?c.declined+' declined':null].filter(Boolean);status.textContent=state.connected?('Connected to '+state.serial+' · '+state.notes+' note(s)'+(bits.length?' · '+bits.join(' · '):'')):'Device disconnected';status.classList.toggle('error',!state.connected)}catch(error){if(!silent){status.textContent=error.message;status.classList.add('error')}}try{const review=await api('/api/review');notes=review.notes}catch(error){if(!silent){status.textContent=error.message;status.classList.add('error')}}if(reloadFrame)frame.src='/frame.png?at='+Date.now();if(frame.complete)renderNotes();inflight=false}
function setLive(on){live=on;liveBtn.classList.toggle('on',on);liveBtn.textContent='Live: '+(on?'on':'off')}
document.querySelector('#touch').onclick=()=>setMode('touch');document.querySelector('#note').onclick=()=>setMode('note');document.querySelector('#refresh').onclick=()=>refresh();liveBtn.onclick=()=>setLive(!live);cancel.onclick=resetEditor;
applyBtn.onclick=async()=>{try{const r=await api('/api/apply',{});status.textContent=r.queued?('Handed '+r.queued+' note(s) to the AI.'):'No pending notes to apply.';status.classList.remove('error');await refresh(false)}catch(error){status.textContent=error.message;status.classList.add('error')}};
save.onclick=async()=>{try{if(!selected){setMode('note');hint.textContent='Now click the element in the APK to place this note.';return}await api('/api/note/edit',{id:selected,text:text.value});await refresh(false);resetEditor()}catch(error){status.textContent=error.message;status.classList.add('error')}};
document.querySelectorAll('[data-key]').forEach(button=>button.onclick=async()=>{await api('/api/key',{key:button.dataset.key});setTimeout(refresh,220)});
device.addEventListener('pointerdown',event=>{busy=true;start=nativePoint(event)});
device.addEventListener('pointerup',async event=>{if(!start){busy=false;return}const end=nativePoint(event);try{if(mode==='note'){if(selected){selectNote(notes.find(note=>note.id===selected));return}if(!text.value.trim())throw new Error('Write the change note first.');await api('/api/note',{x:end.x,y:end.y,text:text.value});text.value='';setMode('touch');await refresh(false)}else if(Math.abs(end.x-start.x)>12||Math.abs(end.y-start.y)>12){await api('/api/swipe',{x1:start.x,y1:start.y,x2:end.x,y2:end.y,duration:220});setTimeout(refresh,260)}else{await api('/api/tap',{x:end.x,y:end.y});setTimeout(refresh,220)}}catch(error){status.textContent=error.message;status.classList.add('error')}finally{start=null;busy=false}});
frame.onload=renderNotes;window.addEventListener('resize',renderNotes);
setInterval(()=>{if(live&&!busy&&document.activeElement!==text)refresh(true,true)},2500);
refresh();
</script></body></html>"""


def handler(bridge: Bridge):
    class Handler(BaseHTTPRequestHandler):
        def log_message(self, *_args) -> None:
            return

        def send_json(self, data: dict, status: int = 200) -> None:
            payload = json.dumps(data).encode("utf-8")
            self.send_response(status)
            self.send_header("Content-Type", "application/json")
            self.send_header("Content-Length", str(len(payload)))
            self.end_headers()
            self.wfile.write(payload)

        def do_GET(self) -> None:
            try:
                path = urlparse(self.path).path
                if path == "/frame.png":
                    png = bridge.frame()
                    self.send_response(HTTPStatus.OK)
                    self.send_header("Content-Type", "image/png")
                    self.send_header("Cache-Control", "no-store")
                    self.send_header("Content-Length", str(len(png)))
                    self.end_headers()
                    self.wfile.write(png)
                elif path == "/api/state":
                    self.send_json(bridge.state())
                elif path == "/api/review":
                    self.send_json(review_notes(bridge))
                elif path == "/api/queue":
                    self.send_json(agent_queue(bridge))
                else:
                    payload = PAGE.encode("utf-8")
                    self.send_response(HTTPStatus.OK)
                    self.send_header("Content-Type", "text/html; charset=utf-8")
                    self.send_header("Content-Length", str(len(payload)))
                    self.end_headers()
                    self.wfile.write(payload)
            except Exception as error:
                self.send_json({"error": str(error)}, status=503)

        def do_POST(self) -> None:
            try:
                length = int(self.headers.get("Content-Length", "0"))
                payload = json.loads(self.rfile.read(length) or b"{}")
                path = urlparse(self.path).path
                if path == "/api/tap":
                    bridge.tap(int(payload["x"]), int(payload["y"]))
                elif path == "/api/swipe":
                    bridge.swipe(int(payload["x1"]), int(payload["y1"]), int(payload["x2"]), int(payload["y2"]), int(payload.get("duration", 220)))
                elif path == "/api/key":
                    bridge.key(str(payload["key"]))
                elif path == "/api/note":
                    self.send_json({"ok": True, "note": add_note(payload, bridge)})
                    return
                elif path == "/api/note/edit":
                    edit_note(payload, bridge)
                elif path == "/api/note/delete":
                    edit_note(payload, bridge, delete=True)
                elif path == "/api/apply":
                    self.send_json({"ok": True, "queued": queue_pending(bridge)})
                    return
                elif path == "/api/note/status":
                    self.send_json({"ok": True, "note": set_status(payload, bridge)})
                    return
                else:
                    raise ValueError("Unknown action")
                self.send_json({"ok": True, "notes": bridge.state()["notes"]})
            except Exception as error:
                self.send_json({"error": str(error)}, status=400)

    return Handler


def resolve_serial(explicit: str | None) -> str:
    if explicit:
        return explicit
    found = devices()
    if len(found) != 1:
        raise SystemExit(f"Connect exactly one device or pass --serial. Found: {', '.join(found) or 'none'}")
    return found[0]


def cli_queue(serial: str | None) -> int:
    """Print the agent queue as JSON (read-only, no server needed)."""
    bridge = Bridge(resolve_serial(serial))
    print(json.dumps(agent_queue(bridge), indent=2))
    return 0


def cli_status(serial: str | None, note_id: str, state: str, reply: str) -> int:
    """Update a note's status from the command line (offline writer, use only when the
    bridge server is not running to avoid racing the server's file writes)."""
    bridge = Bridge(resolve_serial(serial))
    result = set_status({"id": note_id, "status": state, "reply": reply}, bridge)
    print(json.dumps({"ok": True, "note": result}))
    return 0


def main() -> int:
    parser = argparse.ArgumentParser(description="Mirror a real NOOP APK through local ADB")
    parser.add_argument("command", nargs="?", default="serve", choices=("serve", "queue", "status"),
                        help="serve (default): run the bridge; queue: print the agent queue; status: update a note")
    parser.add_argument("--serial", help="ADB serial. Required when more than one device is connected.")
    parser.add_argument("--port", type=int, default=8787)
    parser.add_argument("--id", help="Note id (status command)")
    parser.add_argument("--state", choices=STATUSES, help="New status (status command)")
    parser.add_argument("--reply", default="", help="Agent reply to attach (status command)")
    args = parser.parse_args()
    if not ADB.exists():
        raise SystemExit(f"adb not found: {ADB}")

    if args.command == "queue":
        return cli_queue(args.serial)
    if args.command == "status":
        if not args.id or not args.state:
            raise SystemExit("status needs --id and --state")
        return cli_status(args.serial, args.id, args.state, args.reply)

    serial = resolve_serial(args.serial)
    server = ThreadingHTTPServer(("127.0.0.1", args.port), handler(Bridge(serial)))
    print(f"NOOP APK live review: http://127.0.0.1:{args.port} (serial {serial})")
    print(f"Notes: {NOTES}")
    print(f"Agent queue: GET http://127.0.0.1:{args.port}/api/queue  |  report back: POST /api/note/status")
    server.serve_forever()


if __name__ == "__main__":
    raise SystemExit(main())
