// Stand-in for the glasses: authenticates, logs every server message, and auto-answers
// permission_request (allow by default; set CLV_AUTO=deny to deny). Run from relay/.
import { WebSocket } from "ws";

const url = process.argv[2] ?? "ws://127.0.0.1:4317/ws";
const token = process.argv[3] ?? process.env.CLV_TOKEN;
if (!token) {
  console.error("usage: node scripts/fake-glasses.mjs <ws-url> <token>   (or set CLV_TOKEN)");
  process.exit(1);
}
const ws = new WebSocket(url);
ws.on("open", () => {
  console.log("connected → hello");
  ws.send(JSON.stringify({ type: "hello", token }));
});
ws.on("message", (d) => {
  const m = JSON.parse(d.toString());
  console.log("<-", JSON.stringify(m));
  if (m.type === "permission_request") {
    const decision = process.env.CLV_AUTO === "deny" ? "deny" : "allow";
    console.log("->", decision, "for", m.tool, "—", m.description);
    ws.send(JSON.stringify({ type: "permission_response", session: m.session, id: m.id, decision }));
  }
});
ws.on("close", () => console.log("closed"));
ws.on("error", (e) => console.error("error:", e.message));
