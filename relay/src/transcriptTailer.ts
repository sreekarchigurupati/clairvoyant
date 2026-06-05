import * as fs from "node:fs";
import { parseTranscriptLine } from "./transcriptParser.js";
import type { ServerMessage } from "./protocol.js";

export class TranscriptTailer {
  private pos = 0;
  private carry = "";
  private timer?: ReturnType<typeof setInterval>;
  private started = false;

  constructor(
    private readonly file: string,
    private readonly session: string,
    private readonly emit: (msg: ServerMessage) => void,
    private readonly opts: { backfillLines?: number; pollIntervalMs?: number } = {},
  ) {}

  start(): void {
    if (this.started) return;
    this.started = true;
    this.backfill();
    this.timer = setInterval(() => this.pump(), this.opts.pollIntervalMs ?? 300);
    this.timer.unref?.();
  }

  stop(): void {
    if (this.timer) clearInterval(this.timer);
    this.timer = undefined;
    this.started = false;
  }

  /** Read bytes appended since the last read and emit parsed events. Public for tests. */
  pump(): void {
    let stat: fs.Stats;
    try {
      stat = fs.statSync(this.file);
    } catch {
      return; // not present yet
    }
    if (stat.size < this.pos) {
      this.pos = 0; // truncated/rotated — restart
      this.carry = "";
    }
    if (stat.size === this.pos) return;
    const fd = fs.openSync(this.file, "r");
    try {
      const length = stat.size - this.pos;
      const buf = Buffer.alloc(length);
      fs.readSync(fd, buf, 0, length, this.pos);
      this.pos = stat.size;
      this.consume(buf.toString("utf8"));
    } finally {
      fs.closeSync(fd);
    }
  }

  private consume(chunk: string): void {
    this.carry += chunk;
    let nl: number;
    while ((nl = this.carry.indexOf("\n")) !== -1) {
      const line = this.carry.slice(0, nl);
      this.carry = this.carry.slice(nl + 1);
      if (line) for (const m of parseTranscriptLine(line, this.session)) this.emit(m);
    }
  }

  private backfill(): void {
    let content: string;
    try {
      content = fs.readFileSync(this.file, "utf8");
    } catch {
      return; // not present yet; pump() picks it up once it appears
    }
    this.pos = Buffer.byteLength(content, "utf8");
    const lines = content.split("\n").filter(Boolean);
    const n = this.opts.backfillLines ?? 40;
    for (const line of lines.slice(-n)) {
      for (const m of parseTranscriptLine(line, this.session)) this.emit(m);
    }
  }
}
