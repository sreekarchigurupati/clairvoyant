import fs from "node:fs";
import path from "node:path";

export interface InstallResult {
  changed: boolean;
  warnings: string[];
}

/** 12h — long enough that escalated requests effectively "stay pending". */
export const DEFAULT_HOOK_TIMEOUT = 43200;

export function installHook(
  settingsPath: string,
  hookCommand: string,
  opts: { timeout?: number } = {},
): InstallResult {
  const timeout = opts.timeout ?? DEFAULT_HOOK_TIMEOUT;
  const warnings: string[] = [];

  let settings: Record<string, any> = {};
  if (fs.existsSync(settingsPath)) {
    const raw = fs.readFileSync(settingsPath, "utf8").trim();
    if (raw) settings = JSON.parse(raw);
  }

  settings.hooks ??= {};

  const deny = settings.permissions?.deny;
  if (Array.isArray(deny) && deny.length > 0) {
    warnings.push(
      `permissions.deny has ${deny.length} rule(s); a matching deny still blocks a glasses-approved call (deny > hook).`,
    );
  }

  // PermissionRequest carries the prompts; PreToolUse is session tracking only.
  let changed = false;
  for (const event of ["PermissionRequest", "PreToolUse"]) {
    settings.hooks[event] ??= [];
    const already = settings.hooks[event].some((entry: any) =>
      (entry?.hooks ?? []).some((h: any) => h?.command === hookCommand),
    );
    if (already) continue;
    settings.hooks[event].push({
      matcher: "*",
      hooks: [{ type: "command", command: hookCommand, timeout }],
    });
    changed = true;
  }
  if (!changed) return { changed: false, warnings };

  fs.mkdirSync(path.dirname(settingsPath), { recursive: true });
  fs.writeFileSync(settingsPath, JSON.stringify(settings, null, 2) + "\n");
  return { changed: true, warnings };
}
