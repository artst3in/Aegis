import { useState, useEffect } from "react";

const C = {
  bg: "#050508",
  surface: "#0a1214",
  panel: "#0d1a1e",
  border: "#1a3a40",
  cyan: "#00d4d4",
  cyanDim: "#007a7a",
  cyanBright: "#40f0f0",
  cyanGlow: "rgba(0,212,212,0.15)",
  cyanGlow2: "rgba(0,212,212,0.08)",
  text: "#e0f0f0",
  textDim: "#6a8a8a",
  red: "#e53935",
  redGlow: "rgba(229,57,53,0.2)",
  green: "#4caf50",
  orange: "#ff9800",
};

function HexShape({ size = 40, color = C.cyan, fill = "transparent", opacity = 1, glow = false, children, onClick, style = {} }) {
  // Regular hexagon: all sides equal. Pointy-top.
  // Bounding box is square for clean rendering.
  const r = size / 2;
  const s = size;
  const points = Array.from({length: 6}, (_, i) => {
    const angle = (Math.PI / 3) * i - Math.PI / 2;
    return `${s/2 + r * Math.cos(angle)},${s/2 + r * Math.sin(angle)}`;
  }).join(" ");
  
  return (
    <div style={{ position: "relative", width: s, height: s, cursor: onClick ? "pointer" : "default", ...style }} onClick={onClick}>
      {glow && (
        <div style={{
          position: "absolute", inset: -8, borderRadius: "50%",
          background: `radial-gradient(circle, ${C.cyanGlow} 0%, transparent 70%)`,
          filter: "blur(4px)", pointerEvents: "none"
        }} />
      )}
      <svg width={s} height={s} viewBox={`0 0 ${s} ${s}`} style={{ position: "absolute", top: 0, left: 0 }}>
        <polygon points={points} fill={fill} stroke={color} strokeWidth="1.5" opacity={opacity} />
      </svg>
      <div style={{
        position: "absolute", inset: 0, display: "flex", alignItems: "center",
        justifyContent: "center", zIndex: 1, fontSize: size * 0.35,
        color: C.text, fontFamily: "'Inter', serif"
      }}>
        {children}
      </div>
    </div>
  );
}

function GlassPanel({ children, style = {}, glow = false }) {
  return (
    <div style={{
      background: C.panel, border: `1px solid ${C.border}`,
      borderRadius: 12, padding: 16, position: "relative",
      boxShadow: glow ? `0 0 20px ${C.cyanGlow}, inset 0 1px 0 rgba(0,212,212,0.1)` : "none",
      ...style
    }}>
      {children}
    </div>
  );
}

function StatusDot({ status }) {
  const color = status === "online" ? C.green : status === "away" ? C.orange : C.textDim;
  return (
    <div style={{
      width: 8, height: 8, borderRadius: "50%", background: color,
      boxShadow: status === "online" ? `0 0 6px ${C.green}` : "none"
    }} />
  );
}

function ChatListScreen({ onSelect }) {
  const contacts = [
    { name: "Zippy", status: "online", lastMsg: "I love you 💕", time: "07:02", initial: "Z" },
    { name: "Flozzy", status: "online", lastMsg: "Good morning!", time: "06:45", initial: "F" },
    { name: "Alja", status: "away", lastMsg: "See you Thursday", time: "Yesterday", initial: "A" },
    { name: "Andres", status: "offline", lastMsg: "Lucardo says hi", time: "Yesterday", initial: "An" },
    { name: "Kelcie", status: "online", lastMsg: "📷 Photo", time: "06:30", initial: "K" },
  ];
  
  return (
    <div style={{ flex: 1, overflow: "auto", padding: "0 12px" }}>
      {contacts.map((c, i) => (
        <div key={i} onClick={() => onSelect(c)}
          style={{
            display: "flex", alignItems: "center", gap: 12, padding: "14px 8px",
            borderBottom: `1px solid ${C.border}`, cursor: "pointer",
            transition: "background 0.2s",
          }}
          onMouseEnter={e => e.currentTarget.style.background = C.cyanGlow2}
          onMouseLeave={e => e.currentTarget.style.background = "transparent"}
        >
          <HexShape size={44} color={c.status === "online" ? C.cyan : C.border} fill={C.surface} glow={c.status === "online"}>
            <span style={{ fontSize: 16, fontWeight: "bold" }}>{c.initial}</span>
          </HexShape>
          <div style={{ flex: 1, minWidth: 0 }}>
            <div style={{ display: "flex", justifyContent: "space-between", alignItems: "center" }}>
              <span style={{ color: C.text, fontSize: 15, fontWeight: 600, fontFamily: "'Inter', serif" }}>{c.name}</span>
              <span style={{ color: C.textDim, fontSize: 11 }}>{c.time}</span>
            </div>
            <div style={{ display: "flex", alignItems: "center", gap: 6, marginTop: 3 }}>
              <StatusDot status={c.status} />
              <span style={{ color: C.textDim, fontSize: 13, overflow: "hidden", textOverflow: "ellipsis", whiteSpace: "nowrap" }}>
                {c.lastMsg}
              </span>
            </div>
          </div>
        </div>
      ))}
    </div>
  );
}

function ChatScreen({ contact, onBack }) {
  const [msg, setMsg] = useState("");
  const messages = [
    { from: "them", text: "Good morning babe ❤️", time: "06:30" },
    { from: "me", text: "Morning! How's Kelcie?", time: "06:31" },
    { from: "them", text: "She's eating breakfast 😊", time: "06:32" },
    { from: "them", text: "📷 Photo", time: "06:33" },
    { from: "me", text: "My little one 💕", time: "06:35" },
    { from: "them", text: contact?.lastMsg || "I love you 💕", time: "07:02" },
  ];
  
  return (
    <div style={{ display: "flex", flexDirection: "column", height: "100%" }}>
      <div style={{
        display: "flex", alignItems: "center", gap: 12, padding: "12px 16px",
        borderBottom: `1px solid ${C.border}`, background: C.surface
      }}>
        <div onClick={onBack} style={{ cursor: "pointer", color: C.cyan, fontSize: 20 }}>←</div>
        <HexShape size={34} color={C.cyan} fill={C.surface} glow>
          <span style={{ fontSize: 13, fontWeight: "bold" }}>{contact?.initial}</span>
        </HexShape>
        <div>
          <div style={{ color: C.text, fontSize: 15, fontWeight: 600, fontFamily: "'Inter', serif" }}>{contact?.name}</div>
          <div style={{ color: C.green, fontSize: 11 }}>online · SimpleX</div>
        </div>
      </div>
      
      <div style={{ flex: 1, overflow: "auto", padding: 16, display: "flex", flexDirection: "column", gap: 8 }}>
        {messages.map((m, i) => (
          <div key={i} style={{
            alignSelf: m.from === "me" ? "flex-end" : "flex-start",
            maxWidth: "75%",
          }}>
            <div style={{
              background: m.from === "me" ? "rgba(0,212,212,0.15)" : C.surface,
              border: `1px solid ${m.from === "me" ? C.cyanDim : C.border}`,
              borderRadius: m.from === "me" ? "16px 16px 4px 16px" : "16px 16px 16px 4px",
              padding: "10px 14px",
            }}>
              <div style={{ color: C.text, fontSize: 14 }}>{m.text}</div>
              <div style={{ color: C.textDim, fontSize: 10, textAlign: "right", marginTop: 4 }}>{m.time}</div>
            </div>
          </div>
        ))}
      </div>
      
      <div style={{
        display: "flex", gap: 8, padding: "12px 16px",
        borderTop: `1px solid ${C.border}`, background: C.surface
      }}>
        <input
          value={msg} onChange={e => setMsg(e.target.value)}
          placeholder="Message..."
          style={{
            flex: 1, background: C.panel, border: `1px solid ${C.border}`,
            borderRadius: 20, padding: "10px 16px", color: C.text,
            fontSize: 14, outline: "none", fontFamily: "'Inter', serif",
          }}
          onFocus={e => e.target.style.borderColor = C.cyan}
          onBlur={e => e.target.style.borderColor = C.border}
        />
        <HexShape size={38} color={C.cyan} fill={C.cyanGlow} onClick={() => setMsg("")}>
          <span style={{ fontSize: 16 }}>↑</span>
        </HexShape>
      </div>
    </div>
  );
}

function StatusScreen() {
  const family = [
    { name: "You", status: "online", battery: 67, loc: "Antwerp" },
    { name: "Zippy", status: "online", battery: 82, loc: "Nairobi" },
    { name: "Kelcie", status: "online", battery: 91, loc: "Nairobi" },
    { name: "Flozzy", status: "online", battery: 44, loc: "Homabay" },
    { name: "Alja", status: "away", battery: 73, loc: "Den Haag" },
    { name: "Aurora", status: "online", battery: 99, loc: "Cloud" },
  ];
  
  const hexSize = 72;
  const gap = 6;
  const colStep = hexSize * 0.75 + gap;
  const rowStep = hexSize * 1.1547 + gap;
  
  return (
    <div style={{ flex: 1, padding: 16, overflow: "auto" }}>
      <div style={{ color: C.textDim, fontSize: 11, letterSpacing: 2, textTransform: "uppercase", marginBottom: 16, textAlign: "center", fontFamily: "'Inter', serif" }}>
        Family Status
      </div>
      
      <div style={{ display: "flex", flexWrap: "wrap", gap: 8, justifyContent: "center", padding: "0 8px" }}>
        {family.map((f, i) => (
          <GlassPanel key={i} glow={f.status === "online"} style={{ 
            width: "calc(50% - 8px)", padding: 12,
            display: "flex", alignItems: "center", gap: 10
          }}>
            <HexShape size={42} color={f.status === "online" ? C.cyan : C.border} fill={C.surface} glow={f.status === "online"}>
              <span style={{ fontSize: 14, fontWeight: "bold" }}>{f.name[0]}</span>
            </HexShape>
            <div style={{ flex: 1, minWidth: 0 }}>
              <div style={{ color: C.text, fontSize: 13, fontWeight: 600, fontFamily: "'Inter', serif" }}>{f.name}</div>
              <div style={{ color: C.textDim, fontSize: 10 }}>{f.loc}</div>
              <div style={{ display: "flex", alignItems: "center", gap: 4, marginTop: 4 }}>
                <div style={{ flex: 1, height: 3, background: C.border, borderRadius: 2 }}>
                  <div style={{
                    width: `${f.battery}%`, height: "100%", borderRadius: 2,
                    background: f.battery > 20 ? C.cyan : C.red,
                  }} />
                </div>
                <span style={{ color: C.textDim, fontSize: 9 }}>{f.battery}%</span>
              </div>
            </div>
          </GlassPanel>
        ))}
      </div>
      
      <GlassPanel style={{ marginTop: 16 }}>
        <div style={{ color: C.textDim, fontSize: 11, letterSpacing: 1, marginBottom: 8 }}>PROTOCOL</div>
        <div style={{ display: "flex", justifyContent: "space-between", alignItems: "center" }}>
          <span style={{ color: C.orange, fontSize: 13 }}>SimpleX · Luna offline</span>
          <span style={{ color: C.textDim, fontSize: 11 }}>WireGuard: standby</span>
        </div>
      </GlassPanel>
    </div>
  );
}

function PanicScreen() {
  const [armed, setArmed] = useState(false);
  const [countdown, setCountdown] = useState(null);
  
  useEffect(() => {
    if (countdown !== null && countdown > 0) {
      const t = setTimeout(() => setCountdown(countdown - 1), 1000);
      return () => clearTimeout(t);
    } else if (countdown === 0) {
      setArmed(true);
      setCountdown(null);
    }
  }, [countdown]);
  
  return (
    <div style={{
      flex: 1, display: "flex", flexDirection: "column",
      alignItems: "center", justifyContent: "center", gap: 24, padding: 24
    }}>
      <div style={{ color: C.textDim, fontSize: 11, letterSpacing: 2, textTransform: "uppercase", fontFamily: "'Inter', serif" }}>
        {armed ? "PANIC ACTIVE · SILENT" : "EMERGENCY"}
      </div>
      
      <div style={{ position: "relative" }}>
        {(armed || countdown !== null) && (
          <div style={{
            position: "absolute", inset: -20, borderRadius: "50%",
            background: `radial-gradient(circle, ${armed ? C.redGlow : C.cyanGlow} 0%, transparent 70%)`,
            animation: "pulse 2s ease-in-out infinite",
          }} />
        )}
        <HexShape
          size={120}
          color={armed ? C.red : countdown !== null ? C.orange : C.cyan}
          fill={armed ? C.redGlow : "transparent"}
          glow={armed}
          onClick={() => {
            if (!armed && countdown === null) setCountdown(3);
            if (armed) { setArmed(false); }
          }}
        >
          <div style={{ textAlign: "center" }}>
            {countdown !== null ? (
              <span style={{ fontSize: 36, fontWeight: "bold", color: C.orange }}>{countdown}</span>
            ) : armed ? (
              <span style={{ fontSize: 14, color: C.red, fontWeight: "bold" }}>ACTIVE</span>
            ) : (
              <span style={{ fontSize: 14, color: C.cyan }}>PANIC</span>
            )}
          </div>
        </HexShape>
      </div>
      
      <div style={{ color: C.textDim, fontSize: 12, textAlign: "center", maxWidth: 240 }}>
        {armed
          ? "Silent alert sent. GPS broadcasting. Family notified."
          : countdown !== null
            ? "Hold to confirm..."
            : "Tap to activate silent alarm. Family will be notified."}
      </div>
      
      {armed && (
        <GlassPanel glow style={{ width: "100%", maxWidth: 280 }}>
          <div style={{ display: "flex", justifyContent: "space-between", alignItems: "center" }}>
            <div>
              <div style={{ color: C.red, fontSize: 13, fontWeight: 600 }}>Broadcasting</div>
              <div style={{ color: C.textDim, fontSize: 11 }}>GPS: -1.2921, 36.8219</div>
            </div>
            <HexShape size={36} color={C.red} fill={C.redGlow} onClick={() => setArmed(false)}>
              <span style={{ fontSize: 10, color: C.red }}>STOP</span>
            </HexShape>
          </div>
        </GlassPanel>
      )}
      
      {armed && (
        <GlassPanel style={{ width: "100%", maxWidth: 280, marginTop: 8 }}>
          <div style={{ color: C.textDim, fontSize: 11, marginBottom: 8 }}>SIREN (optional)</div>
          <HexShape size={48} color={C.red} fill="transparent" onClick={() => {}} style={{ margin: "0 auto" }}>
            <span style={{ fontSize: 10, color: C.red }}>🔊</span>
          </HexShape>
          <div style={{ color: C.textDim, fontSize: 10, textAlign: "center", marginTop: 8 }}>
            Requires confirmation. Cannot be stopped by thief.
          </div>
        </GlassPanel>
      )}
    </div>
  );
}

function BottomNav({ active, onChange }) {
  const tabs = [
    { id: "chats", label: "Chats", icon: "✉" },
    { id: "status", label: "Status", icon: "◈" },
    { id: "panic", label: "Panic", icon: "⚠" },
    { id: "settings", label: "Settings", icon: "⚙" },
  ];
  
  return (
    <div style={{
      display: "flex", justifyContent: "space-around", padding: "8px 0 12px",
      borderTop: `1px solid ${C.border}`, background: C.surface,
    }}>
      {tabs.map(t => (
        <div key={t.id} onClick={() => onChange(t.id)}
          style={{
            display: "flex", flexDirection: "column", alignItems: "center", gap: 2,
            cursor: "pointer", opacity: active === t.id ? 1 : 0.4,
            transition: "opacity 0.2s",
          }}>
          <HexShape
            size={28}
            color={active === t.id ? (t.id === "panic" ? C.red : C.cyan) : C.textDim}
            fill={active === t.id ? C.cyanGlow : "transparent"}
          >
            <span style={{ fontSize: 12 }}>{t.icon}</span>
          </HexShape>
          <span style={{
            fontSize: 9, letterSpacing: 1,
            color: active === t.id ? (t.id === "panic" ? C.red : C.cyan) : C.textDim,
            fontFamily: "'Inter', serif"
          }}>
            {t.label}
          </span>
        </div>
      ))}
    </div>
  );
}

function Header() {
  return (
    <div style={{
      display: "flex", justifyContent: "space-between", alignItems: "center",
      padding: "14px 16px", borderBottom: `1px solid ${C.border}`,
    }}>
      <span style={{
        color: C.cyan, fontSize: 18, fontWeight: 700, letterSpacing: 4,
        fontFamily: "'Inter', serif",
        textShadow: `0 0 12px ${C.cyanGlow}`
      }}>
        AEGIS
      </span>
      <span style={{ color: C.orange, fontSize: 11, fontFamily: "'Inter', serif" }}>
        SimpleX · Luna offline
      </span>
    </div>
  );
}

export default function AegisApp() {
  const [tab, setTab] = useState("chats");
  const [chatContact, setChatContact] = useState(null);
  
  return (
    <div style={{
      width: "100%", maxWidth: 390, margin: "0 auto",
      height: "100vh", maxHeight: 844,
      background: C.bg, color: C.text,
      display: "flex", flexDirection: "column",
      fontFamily: "'Inter', serif",
      overflow: "hidden",
      borderRadius: 20, border: `1px solid ${C.border}`,
    }}>
      <style>{`
        @keyframes pulse {
          0%, 100% { opacity: 0.5; transform: scale(1); }
          50% { opacity: 1; transform: scale(1.05); }
        }
        * { box-sizing: border-box; }
        ::-webkit-scrollbar { width: 4px; }
        ::-webkit-scrollbar-thumb { background: ${C.border}; border-radius: 4px; }
      `}</style>
      
      {chatContact ? (
        <ChatScreen contact={chatContact} onBack={() => setChatContact(null)} />
      ) : (
        <>
          <Header />
          {tab === "chats" && <ChatListScreen onSelect={c => setChatContact(c)} />}
          {tab === "status" && <StatusScreen />}
          {tab === "panic" && <PanicScreen />}
          {tab === "settings" && (
            <div style={{ flex: 1, display: "flex", alignItems: "center", justifyContent: "center" }}>
              <GlassPanel style={{ padding: 24, textAlign: "center" }}>
                <div style={{ color: C.textDim, fontSize: 13 }}>Settings coming soon</div>
                <div style={{ color: C.cyan, fontSize: 11, marginTop: 8 }}>Project Aether · v2026.05</div>
              </GlassPanel>
            </div>
          )}
          <BottomNav active={tab} onChange={setTab} />
        </>
      )}
    </div>
  );
}
