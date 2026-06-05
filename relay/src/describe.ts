const MAX = 200;

function clip(s: string): string {
  return s.length > MAX ? s.slice(0, MAX - 1) + "…" : s;
}

export function describeTool(toolName: string, input: Record<string, unknown>): string {
  switch (toolName) {
    case "Bash": {
      const cmd = typeof input.command === "string" ? input.command : "";
      return clip(cmd || "Bash command");
    }
    case "Edit":
    case "Write":
    case "MultiEdit":
    case "NotebookEdit": {
      const p = (input.file_path ?? input.notebook_path ?? "") as string;
      return clip(`${toolName} ${p}`.trim());
    }
    case "Read":
    case "Glob":
    case "Grep": {
      const p = (input.file_path ?? input.path ?? input.pattern ?? "") as string;
      return clip(`${toolName} ${p}`.trim());
    }
    case "WebFetch":
    case "WebSearch": {
      const q = (input.url ?? input.query ?? "") as string;
      return clip(`${toolName} ${q}`.trim());
    }
    default:
      return toolName;
  }
}
