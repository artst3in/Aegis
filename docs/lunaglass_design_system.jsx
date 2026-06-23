import { useState } from "react";

const C = {
  bg: "#050508", surface: "#0a1214", panel: "#0d1a1e", border: "#1a3a40",
  cyan: "#00FFFF", cyanDim: "#007a7a", text: "#e0f0f0", textDim: "#6a8a8a",
  red: "#FF0000", green: "#00FF00", orange: "#ff9800",
};

const Hex = ({ size = 40, fill = "transparent", stroke = C.cyan, glow, children, style = {} }) => {
  const r = size / 2;
  const h = r * 0.8660254;
  const v = [[r+r,r],[r+r/2,r+h],[r-r/2,r+h],[r-r,r],[r-r/2,r-h],[r+r/2,r-h]];
  const ei = [[4,5],[3,4],[2,3],[1,2],[0,1],[5,0]];
  const rO = r + 1.2, rI = r - 1.2;
  return (
    <div style={{ position: "relative", width: size, height: size, display: "inline-flex", alignItems: "center", justifyContent: "center", ...style }}>
      {glow && <div style={{ position: "absolute", inset: -6, borderRadius: "50%", background: "radial-gradient(circle, rgba(0,255,255,0.2) 0%, transparent 70%)", filter: "blur(6px)" }} />}
      <svg width={size} height={size} viewBox={`0 0 ${size} ${size}`} style={{ position: "absolute" }}>
        <defs><radialGradient id={`hg${size}`}><stop offset="0%" stopColor="rgba(0,255,255,0.3)" /><stop offset="100%" stopColor="rgba(0,120,130,0.08)" /></radialGradient></defs>
        <polygon points={v.map(p=>`${p[0].toFixed(1)},${p[1].toFixed(1)}`).join(" ")} fill={fill === "gradient" ? `url(#hg${size})` : fill} stroke="none" />
        {ei.map(([a,b],i) => {
          const aA=(Math.PI/180)*(60*a), aB=(Math.PI/180)*(60*b);
          const pts = `${(r+rO*Math.cos(aA)).toFixed(1)},${(r+rO*Math.sin(aA)).toFixed(1)} ${(r+rO*Math.cos(aB)).toFixed(1)},${(r+rO*Math.sin(aB)).toFixed(1)} ${(r+rI*Math.cos(aB)).toFixed(1)},${(r+rI*Math.sin(aB)).toFixed(1)} ${(r+rI*Math.cos(aA)).toFixed(1)},${(r+rI*Math.sin(aA)).toFixed(1)}`;
          return <polygon key={i} points={pts} fill={stroke} opacity={0.85} />;
        })}
      </svg>
      <div style={{ position: "relative", zIndex: 1, display: "flex", alignItems: "center", justifyContent: "center", fontSize: size * 0.28, color: C.text, fontFamily: "Inter, sans-serif" }}>{children}</div>
    </div>
  );
};

const Swatch = ({ color, label, hex }) => (
  <div style={{ display: "flex", alignItems: "center", gap: 10, marginBottom: 6 }}>
    <div style={{ width: 28, height: 28, borderRadius: 4, background: color, border: `1px solid ${C.border}` }} />
    <div>
      <div style={{ color: C.text, fontSize: 11, fontWeight: 600 }}>{label}</div>
      <div style={{ color: C.textDim, fontSize: 10, fontFamily: "monospace" }}>{hex}</div>
    </div>
  </div>
);

const Section = ({ title, children }) => (
  <div style={{ marginBottom: 28 }}>
    <div style={{ color: C.cyan, fontSize: 10, letterSpacing: 3, textTransform: "uppercase", marginBottom: 10, borderBottom: `1px solid ${C.border}`, paddingBottom: 4 }}>{title}</div>
    {children}
  </div>
);

export default function LunaGlassSystem() {
  return (
    <div style={{ background: C.bg, minHeight: "100vh", padding: 24, fontFamily: "Inter, sans-serif", color: C.text }}>
      <div style={{ textAlign: "center", marginBottom: 32, position: "relative" }}>
        <div style={{ position: "absolute", inset: 0, display: "flex", justifyContent: "center", alignItems: "center", opacity: 0.05 }}>
          <Hex size={220} stroke={C.cyan} />
        </div>
        <div style={{ position: "relative" }}>
          <div style={{ color: C.cyan, fontSize: 28, fontWeight: 700, letterSpacing: 8, textShadow: "0 0 20px rgba(0,255,255,0.3)" }}>LUNAGLASS</div>
          <div style={{ color: C.textDim, fontSize: 12, letterSpacing: 3, marginTop: 4 }}>DESIGN SYSTEM</div>
          <div style={{ color: C.textDim, fontSize: 10, marginTop: 2 }}>Project Aether · v2026.05 · Universal</div>
        </div>
      </div>

      <Section title="Identity">
        <div style={{ color: C.textDim, fontSize: 11, lineHeight: 1.8 }}>
          <div><span style={{ color: C.cyan }}>Framework:</span> Project Aether</div>
          <div><span style={{ color: C.cyan }}>Design language:</span> LunaGlass</div>
          <div><span style={{ color: C.cyan }}>Font:</span> Inter (sans-serif, OFL license, 200KB)</div>
          <div><span style={{ color: C.cyan }}>Hex orientation:</span> flat-top, everywhere, no exceptions</div>
          <div><span style={{ color: C.cyan }}>Primary color:</span> #00FFFF (pure cyan)</div>
          <div><span style={{ color: C.cyan }}>Surface:</span> dark backgrounds, light-on-dark only</div>
          <div><span style={{ color: C.cyan }}>Applies to:</span> LunaOS · Aegis · LunaSpace · web · print · all Aether surfaces</div>
        </div>
      </Section>

      <Section title="Color Palette">
        <div style={{ display: "grid", gridTemplateColumns: "1fr 1fr", gap: "0 20px" }}>
          <Swatch color="#00FFFF" label="Primary" hex="#00FFFF" />
          <Swatch color="#007a7a" label="Primary Dim" hex="#007A7A" />
          <Swatch color="#050508" label="Background" hex="#050508" />
          <Swatch color="#0a1214" label="Surface" hex="#0A1214" />
          <Swatch color="#0d1a1e" label="Panel" hex="#0D1A1E" />
          <Swatch color="#1a3a40" label="Border" hex="#1A3A40" />
          <Swatch color="#e0f0f0" label="Text Primary" hex="#E0F0F0" />
          <Swatch color="#6a8a8a" label="Text Secondary" hex="#6A8A8A" />
          <Swatch color="#FF0000" label="Danger" hex="#E53935" />
          <Swatch color="#00FF00" label="Success" hex="#4CAF50" />
          <Swatch color="#ff9800" label="Warning" hex="#FF9800" />
        </div>
      </Section>

      <Section title="Typography · Inter">
        <div style={{ display: "flex", flexDirection: "column", gap: 8 }}>
          <div><span style={{ fontSize: 22, fontWeight: 700, color: C.cyan }}>Display Title</span> <span style={{ color: C.textDim, fontSize: 9 }}>22px / 700</span></div>
          <div><span style={{ fontSize: 16, fontWeight: 700 }}>Section Header</span> <span style={{ color: C.textDim, fontSize: 9 }}>16px / 700</span></div>
          <div><span style={{ fontSize: 14, fontWeight: 600 }}>Component Label</span> <span style={{ color: C.textDim, fontSize: 9 }}>14px / 600</span></div>
          <div><span style={{ fontSize: 13 }}>Body text for content</span> <span style={{ color: C.textDim, fontSize: 9 }}>13px / 400</span></div>
          <div><span style={{ fontSize: 11, color: C.textDim }}>Secondary · Metadata</span> <span style={{ color: C.textDim, fontSize: 9 }}>11px / 400</span></div>
          <div><span style={{ fontSize: 9, color: C.textDim, letterSpacing: 3, textTransform: "uppercase" }}>SECTION LABEL</span> <span style={{ color: C.textDim, fontSize: 9 }}>9px / 3px tracking</span></div>
        </div>
      </Section>

      <Section title="Hexagon Specification">
        <div style={{ color: C.textDim, fontSize: 10, marginBottom: 4 }}>Flat-top everywhere. Edges: trapezoid (inner/outer radius). Radial cuts at vertices. No round caps.</div>
        <div style={{ display: "flex", gap: 14, alignItems: "flex-end", flexWrap: "wrap", marginTop: 8 }}>
          {[{s:80,l:"XL"},{s:56,l:"LG"},{s:44,l:"MD"},{s:32,l:"SM"},{s:20,l:"XS"}].map((h,i) => (
            <div key={i} style={{ textAlign: "center" }}>
              <Hex size={h.s} fill="gradient" stroke={C.cyan} glow={i<2}><span style={{fontWeight:"bold"}}>{h.l}</span></Hex>
              <div style={{ color: C.textDim, fontSize: 9, marginTop: 4 }}>{h.s}px</div>
            </div>
          ))}
        </div>
      </Section>

      <Section title="Components">
        <div style={{ marginBottom: 12 }}>
          <div style={{ color: C.textDim, fontSize: 9, marginBottom: 4 }}>GLASS PANEL</div>
          <div style={{ background: C.panel, border: `1px solid ${C.border}`, borderRadius: 12, padding: 14, boxShadow: "0 0 20px rgba(0,255,255,0.08), inset 0 1px 0 rgba(0,255,255,0.06)" }}>
            <div style={{ fontSize: 13 }}>Frosted glass · backdrop-blur(12px) · 12px radius</div>
            <div style={{ fontSize: 11, color: C.textDim, marginTop: 4 }}>Active panels receive cyan glow shadow</div>
          </div>
        </div>
        <div style={{ marginBottom: 12 }}>
          <div style={{ color: C.textDim, fontSize: 9, marginBottom: 4 }}>STATUS INDICATORS</div>
          <div style={{ display: "flex", gap: 16 }}>
            {[{l:"Active",c:C.cyan,g:true},{l:"Success",c:C.green,g:true},{l:"Warning",c:C.orange,g:false},{l:"Inactive",c:C.border,g:false},{l:"Danger",c:C.red,g:true}].map((s,i) => (
              <div key={i} style={{ textAlign: "center" }}>
                <Hex size={36} fill={C.surface} stroke={s.c} glow={s.g}><span style={{fontSize:9}}>●</span></Hex>
                <div style={{ fontSize: 9, color: s.c, marginTop: 2 }}>{s.l}</div>
              </div>
            ))}
          </div>
        </div>
        <div style={{ marginBottom: 12 }}>
          <div style={{ color: C.textDim, fontSize: 9, marginBottom: 4 }}>INPUT + ACTION</div>
          <div style={{ display: "flex", gap: 8, alignItems: "center" }}>
            <Hex size={30} stroke={C.cyan}><span style={{fontSize:14}}>+</span></Hex>
            <div style={{ flex: 1, background: C.panel, border: `1px solid ${C.border}`, borderRadius: 16, padding: "8px 14px", fontSize: 12, color: C.textDim }}>Input field · focus → cyan border</div>
            <Hex size={30} stroke={C.cyan} fill="rgba(0,255,255,0.1)"><span style={{fontSize:12}}>→</span></Hex>
          </div>
        </div>
        <div style={{ marginBottom: 12 }}>
          <div style={{ color: C.textDim, fontSize: 9, marginBottom: 4 }}>HEX SWITCH (replaces Material toggle)</div>
          <div style={{ display: "flex", gap: 16, alignItems: "center" }}>
            <div style={{ position: "relative", width: 52, height: 28 }}>
              <svg viewBox="0 0 52 28" width="52" height="28">
                <path d={`M7,2 L45,2 L50,14 L45,26 L7,26 L2,14 Z`} fill={C.panel} stroke={C.border} strokeWidth="1" />
                <path d={`M30,5 L42,5 L47,14 L42,23 L30,23 L25,14 Z`} fill="rgba(0,255,255,0.2)" stroke={C.cyan} strokeWidth="1.5" style={{ filter: "drop-shadow(0 0 6px rgba(0,255,255,0.4))" }} />
              </svg>
            </div>
            <span style={{ fontSize: 11, color: C.cyan }}>ON</span>
            <div style={{ position: "relative", width: 52, height: 28 }}>
              <svg viewBox="0 0 52 28" width="52" height="28">
                <path d={`M7,2 L45,2 L50,14 L45,26 L7,26 L2,14 Z`} fill={C.panel} stroke={C.border} strokeWidth="1" />
                <path d={`M10,5 L22,5 L27,14 L22,23 L10,23 L5,14 Z`} fill={C.surface} stroke={C.border} strokeWidth="1" />
              </svg>
            </div>
            <span style={{ fontSize: 11, color: C.textDim }}>OFF</span>
          </div>
          <div style={{ fontSize: 11, color: C.textDim, marginTop: 4 }}>Hex thumb slides in hex track · ON: cyan glow + fill · OFF: dark hollow · replaces all Material Switch/Toggle</div>
        </div>
        <div>
          <div style={{ color: C.textDim, fontSize: 9, marginBottom: 4 }}>NAVIGATION BAR</div>
          <div style={{ display: "flex", gap: 16, background: C.surface, padding: "8px 16px", borderRadius: 8, border: `1px solid ${C.border}` }}>
            {[{i:"◈",l:"Home",a:true},{i:"◇",l:"Browse",a:false},{i:"⚙",l:"Config",a:false},{i:"◎",l:"Status",a:false}].map((n,j) => (
              <div key={j} style={{ display:"flex", flexDirection:"column", alignItems:"center", gap:2, opacity:n.a?1:0.4 }}>
                <Hex size={24} stroke={n.a?C.cyan:C.textDim} fill={n.a?"rgba(0,255,255,0.1)":"transparent"}><span style={{fontSize:10}}>{n.i}</span></Hex>
                <span style={{ fontSize:8, color:n.a?C.cyan:C.textDim }}>{n.l}</span>
              </div>
            ))}
          </div>
        </div>
      </Section>

      <Section title="Visual Effects">
        <div style={{ display: "grid", gridTemplateColumns: "1fr 1fr", gap: 8 }}>
          {[{n:"Breathing Glow",d:"3s ease pulse on active elements",p:"P1"},{n:"Frosted Glass",d:"backdrop-blur(12px) on panels",p:"P1"},{n:"Hex Gradient",d:"Radial: bright center → dark edge",p:"P2"},{n:"Double Ring",d:"Inner + 3px gap + outer, gap glows",p:"P2"},{n:"Edge Heat",d:"CCW trapezoid edges, vibrate per edge",p:"P3"},{n:"Hex Grid BG",d:"Faint 40px tessellation behind content",p:"P3"},{n:"Edge Lighting",d:"Top-left source: ±20% per edge",p:"P4"},{n:"Scan Line",d:"1px sweep every 10s, barely visible",p:"P4"},{n:"Hex Assembly",d:"Build from center on transitions",p:"P4"},{n:"Data Rain",d:"4px hex particles drifting. Optional.",p:"P5"}].map((e,i) => (
            <div key={i} style={{ background: C.surface, border: `1px solid ${C.border}`, borderRadius: 6, padding: 8 }}>
              <div style={{ display:"flex", justifyContent:"space-between" }}>
                <span style={{ fontSize:10, fontWeight:600 }}>{e.n}</span>
                <span style={{ fontSize:8, color:C.cyan, fontFamily:"monospace" }}>{e.p}</span>
              </div>
              <div style={{ fontSize:9, color:C.textDim, marginTop:2 }}>{e.d}</div>
            </div>
          ))}
        </div>
      </Section>

      <Section title="Rules">
        <div style={{ display: "grid", gridTemplateColumns: "1fr 1fr", gap: 16 }}>
          <div style={{ fontSize: 10, color: C.textDim, lineHeight: 1.8 }}>
            <div style={{ color: C.cyan, fontWeight: 600, marginBottom: 4 }}>DO</div>
            <div>· Flat-top hexagons everywhere</div>
            <div>· Inter font for all text</div>
            <div>· Dark backgrounds, cyan accents</div>
            <div>· Glow = alive, no glow = inactive</div>
            <div>· Trapezoid edges, radial vertex cuts</div>
            <div>· Behavior from light and color</div>
            <div>· Hex switch for all toggles</div>
          </div>
          <div style={{ fontSize: 10, color: C.textDim, lineHeight: 1.8 }}>
            <div style={{ color: C.red, fontWeight: 600, marginBottom: 4 }}>DON'T</div>
            <div>· No circles for controls</div>
            <div>· No pointy-top hexagons</div>
            <div>· No Material Design icons</div>
            <div>· No light mode</div>
            <div>· No rounded avatars</div>
            <div>· No round-capped edges</div>
            <div>· No Material Switch/Toggle/Checkbox</div>
          </div>
        </div>
      </Section>

      <div style={{ textAlign: "center", marginTop: 24, paddingTop: 16, borderTop: `1px solid ${C.border}` }}>
        <div style={{ display: "flex", justifyContent: "center", gap: 4, marginBottom: 8 }}>
          {[0,1,2,3,4,5,6].map(i => <Hex key={i} size={14} stroke={i===3?C.cyan:C.cyanDim} fill={i===3?"rgba(0,255,255,0.15)":"transparent"} />)}
        </div>
        <div style={{ color: C.textDim, fontSize: 9, letterSpacing: 3 }}>PROJECT AETHER · LUNAGLASS · 2026</div>
        <div style={{ color: C.textDim, fontSize: 8, marginTop: 2 }}>LunaOS · Aegis · LunaSpace · Web · Print · All Surfaces</div>
        <div style={{ color: C.cyan, fontSize: 9, marginTop: 4 }}>dε/dt ≤ 0</div>
      </div>
    </div>
  );
}
