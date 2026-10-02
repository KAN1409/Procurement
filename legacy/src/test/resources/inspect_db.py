#!/usr/bin/env python3
"""
Dumps the live Work Knowledge database copied off the device.

Usage: python3 inspect_db.py [path-to-db-copy]
Reads the .db plus any -wal/-shm sitting beside it.
"""
import os
import sqlite3
import sys
import struct

DB = sys.argv[1] if len(sys.argv) > 1 else "/data/local/tmp/wk/x.db"

conn = sqlite3.connect(DB)
conn.row_factory = sqlite3.Row


def rows(sql, args=()):
    try:
        return conn.execute(sql, args).fetchall()
    except sqlite3.Error as exc:
        print(f"  !! {sql} -> {exc}")
        return []


def decode_vector(blob):
    """Vectors are little-endian float32, 4 bytes each."""
    if not blob:
        return None
    n = len(blob) // 4
    vals = struct.unpack("<%df" % n, blob[: n * 4])
    return n, vals


print("=" * 72)
print("PROJECTS")
for r in rows("select id,name,isArchived,createdAt from projects"):
    print(f"  id={r['id']}  name={r['name']!r}  archived={r['isArchived']}")

print("=" * 72)
print("DOCUMENTS")
docs = rows(
    "select id,projectId,filename,mimeType,fileSize,sha256,pageCount,sheetCount,"
    "status,errorMessage,originalUri from documents order by id"
)
for r in docs:
    print(f"  id={r['id']}  {r['filename']}")
    print(f"      projectId={r['projectId']}  mime={r['mimeType']}")
    print(f"      size={r['fileSize']}  pages={r['pageCount']} sheets={r['sheetCount']}")
    print(f"      status={r['status']}  error={r['errorMessage']!r}")
    print(f"      sha256={r['sha256']}")
    print(f"      uri={r['originalUri']}")

print("=" * 72)
print("CHUNKS (counts per document)")
for r in rows(
    "select documentId, count(*) n, sum(case when embedding is not null then 1 else 0 end) vec"
    " from chunks group by documentId order by documentId"
):
    print(f"  documentId={r['documentId']}  chunks={r['n']}  withVector={r['vec']}")

print("=" * 72)
print("CHUNK PROVENANCE")
for r in rows(
    "select c.id,c.documentId,d.filename,c.chunkIndex,c.pageNumber,c.sheetName,"
    "c.rowStart,c.rowEnd,c.cellRange,length(c.embedding) embLen,substr(c.content,1,70) txt"
    " from chunks c join documents d on d.id=c.documentId"
    " order by c.documentId, c.chunkIndex"
):
    print(
        f"  chunk {r['id']:>3} doc={r['documentId']} idx={r['chunkIndex']:>2} "
        f"page={r['pageNumber']} sheet={r['sheetName']!r} "
        f"rows={r['rowStart']}-{r['rowEnd']} range={r['cellRange']!r} "
        f"embBytes={r['embLen']}"
    )
    print(f"        {r['txt']!r}")

print("=" * 72)
print("VECTOR DIMENSIONS (distinct, per document)")
for r in rows(
    "select documentId, length(embedding) bytes, count(*) n from chunks"
    " where embedding is not null group by documentId, length(embedding)"
):
    dims = r["bytes"] // 4
    print(f"  documentId={r['documentId']}  bytes={r['bytes']}  dims={dims}  count={r['n']}")

print("=" * 72)
print("SUMMARIES")
for r in rows("select documentId,modelUsed,length(summary) len,substr(summary,1,120) s from document_summaries"):
    print(f"  documentId={r['documentId']}  model={r['modelUsed']!r}  len={r['len']}")
    print(f"      {r['s']!r}")

print("=" * 72)
print("PROCESSING JOBS")
for r in rows(
    "select documentId,jobType,status,progress,totalSteps,currentStep,errorMessage"
    " from processing_jobs order by documentId"
):
    print(
        f"  doc={r['documentId']} type={r['jobType']} status={r['status']} "
        f"{r['progress']}/{r['totalSteps']} step={r['currentStep']!r} err={r['errorMessage']!r}"
    )

print("=" * 72)
print("CHAT")
for r in rows("select id,projectId,title from chat_sessions"):
    print(f"  session id={r['id']} project={r['projectId']} title={r['title']!r}")
for r in rows("select id,sessionId,role,length(content) len from chat_messages order by id"):
    print(f"    msg id={r['id']} session={r['sessionId']} role={r['role']} len={r['len']}")
