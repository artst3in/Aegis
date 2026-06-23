import { useState, useEffect, useRef } from "react";

function flatTopHex(cx, cy, r) {
  const h = r * 0.8660254;
  return [
    { x: cx + r,       y: cy },
    { x: cx + r * 0.5, y: cy + h },
    { x: cx - r * 0.5, y: cy + h },
    { x: cx - r,       y: cy },
    { x: cx - r * 0.5, y: cy - h },
    { x: cx + r * 0.5, y: cy - h },
  ];
}

function edgesCCW(verts) {
  return [
    [verts[4], verts[5]],
    [verts[3], verts[4]],
    [verts[2], verts[3]],
    [verts[1], verts[2]],
    [verts[0], verts[1]],
    [verts[5], verts[0]],
  ];
}

/*
  Each edge is a trapezoid between an inner hex (r - w/2) and outer hex (r + w/2).
  At each vertex, corners sit on the radial line from center → vertex.
  Outer side is longer (bigger radius), inner side is shorter.
*/
function edgeTrapezoid(vertIdxA, vertIdxB, cx, cy, r, w) {
  const half = w / 2;
  const rOut = r + half;
  const rIn = r - half;

  // Get angles for the two vertices
  function vertAngle(idx) {
    return (Math.PI / 180) * (60 * idx);
  }
  const angA = vertAngle(vertIdxA);
  const angB = vertAngle(vertIdxB);

  const aoX = cx + rOut * Math.cos(angA), aoY = cy + rOut * Math.sin(angA);
  const boX = cx + rOut * Math.cos(angB), boY = cy + rOut * Math.sin(angB);
  const biX = cx + rIn * Math.cos(angB), biY = cy + rIn * Math.sin(angB);
  const aiX = cx + rIn * Math.cos(angA), aiY = cy + rIn * Math.sin(angA);

  return `${aoX.toFixed(1)},${aoY.toFixed(1)} ${boX.toFixed(1)},${boY.toFixed(1)} ${biX.toFixed(1)},${biY.toFixed(1)} ${aiX.toFixed(1)},${aiY.toFixed(1)}`;
}

// CCW edge order from top, using vertex INDICES into the standard flat-top hex
// Vertex indices: 0=right, 1=lower-right, 2=lower-left, 3=left, 4=upper-left, 5=upper-right
const EDGE_INDICES = [
  [4, 5],  // top
  [3, 4],  // upper-left
  [2, 3],  // lower-left
  [1, 2],  // bottom
  [0, 1],  // lower-right
  [5, 0],  // upper-right
];

function HexHold({ size, color, ms, label, active, onDone }) {
  const [progress, setProgress] = useState(0);
  const t0 = useRef(null);
  const raf = useRef(null);

  useEffect(() => {
    if (!active) { setProgress(0); t0.current = null; return; }
    const tick = (ts) => {
      if (!t0.current) t0.current = ts;
      const p = Math.min((ts - t0.current) / ms, 1);
      setProgress(p);
      if (p < 1) raf.current = requestAnimationFrame(tick);
      else onDone?.();
    };
    raf.current = requestAnimationFrame(tick);
    return () => { if (raf.current) cancelAnimationFrame(raf.current); };
  }, [active, ms]);

  const pad = 24;
  const svgSize = size + pad * 2;
  const r = size / 2;
  const cx = svgSize / 2;
  const cy = svgSize / 2;
  const verts = flatTopHex(cx, cy, r);
  const edges = edgesCCW(verts);
  const poly = verts.map(v => `${v.x.toFixed(1)},${v.y.toFixed(1)}`).join(" ");

  const strokeW = Math.max(3, r * 0.04);
  const ei = Math.min(Math.floor(progress * 6), 5);
  const ef = (progress * 6) % 1;
  const done = progress >= 1;

  return (
    <div style={{ position: "relative", width: svgSize, height: svgSize }}>
      {progress > 0 && (
        <div style={{
          position: "absolute", inset: -16, borderRadius: "50%",
          background: `radial-gradient(circle, ${color}22 0%, transparent 60%)`,
          filter: "blur(14px)", pointerEvents: "none", opacity: progress,
        }} />
      )}
      <svg width={svgSize} height={svgSize}>
        <defs><filter id="gl"><feGaussianBlur stdDeviation="5" /></filter></defs>

        {/* Dim hex outline — also as trapezoid edges for consistency */}
        <polygon points={poly} fill="none" stroke="#1a3a40" strokeWidth={strokeW} strokeLinejoin="miter" />

        {/* Heated edges as trapezoids */}
        {EDGE_INDICES.map(([ai, bi], i) => {
          let op = 0;
          if (done) op = 1;
          else if (i < ei) op = 1;
          else if (i === ei) op = 0.1 + ef * 0.9;
          if (op <= 0) return null;

          const trap = edgeTrapezoid(ai, bi, cx, cy, r, strokeW * 3);
          const trapGlow = edgeTrapezoid(ai, bi, cx, cy, r, strokeW * 8);
          return (
            <g key={i}>
              <polygon points={trapGlow} fill={color} opacity={op * 0.15} filter="url(#gl)" />
              <polygon points={trap} fill={color} opacity={op} />
            </g>
          );
        })}
      </svg>

      <div style={{
        position: "absolute", inset: 0, display: "flex", flexDirection: "column",
        alignItems: "center", justifyContent: "center", fontFamily: "Georgia, serif",
      }}>
        <div style={{
          fontSize: r * 0.28, fontWeight: "bold", letterSpacing: 3,
          color: done ? color : "#e0f0f0",
        }}>
          {done ? "ACTIVE" : label}
        </div>
        {active && !done && (
          <div style={{ fontSize: r * 0.14, color: "#6a8a8a", marginTop: 4 }}>
            {Math.ceil((1 - progress) * ms / 1000)}s
          </div>
        )}
      </div>
    </div>
  );
}

export default function Demo() {
  const [pOn, setPOn] = useState(false);
  const [pDone, setPDone] = useState(false);
  const [sOn, setSOn] = useState(false);
  const [sDone, setSDone] = useState(false);

  const bind = (setOn, setDone, isDone) => ({
    onMouseDown: () => { setOn(true); setDone(false); },
    onMouseUp: () => { if (!isDone) setOn(false); },
    onMouseLeave: () => { if (!isDone) setOn(false); },
    onTouchStart: (e) => { e.preventDefault(); setOn(true); setDone(false); },
    onTouchEnd: () => { if (!isDone) setOn(false); },
  });

  return (
    <div style={{
      background: "#050508", minHeight: "100vh", padding: "24px 16px",
      fontFamily: "Georgia, serif", color: "#e0f0f0",
      display: "flex", flexDirection: "column", alignItems: "center", gap: 28,
    }}>
      <div style={{ textAlign: "center" }}>
        <div style={{ color: "#00FFFF", fontSize: 16, fontWeight: 700, letterSpacing: 4 }}>EDGE HEAT</div>
        <div style={{ color: "#6a8a8a", fontSize: 10, marginTop: 4 }}>
          Trapezoid edges · miter joints · no round caps · 3× slow demo
        </div>
      </div>

      <div style={{ textAlign: "center" }}>
        <div style={{ color: "#6a8a8a", fontSize: 10, letterSpacing: 2, marginBottom: 6 }}>PANIC · 9s demo (3s prod)</div>
        <div style={{ cursor: "pointer", userSelect: "none" }} {...bind(setPOn, setPDone, pDone)}>
          <HexHold size={180} color="#e53935" ms={9000} label="PANIC" active={pOn} onDone={() => setPDone(true)} />
        </div>
        <div style={{ color: "#6a8a8a", fontSize: 10, marginTop: 4 }}>
          {pDone ? "ACTIVE" : "Hold · release to cancel"}
        </div>
        {pDone && <div style={{ color: "#e53935", fontSize: 10, marginTop: 4, cursor: "pointer" }} onClick={() => { setPOn(false); setPDone(false); }}>[ reset ]</div>}
      </div>

      <div style={{ textAlign: "center" }}>
        <div style={{ color: "#6a8a8a", fontSize: 10, letterSpacing: 2, marginBottom: 6 }}>SEND · 1.5s demo (500ms prod)</div>
        <div style={{ cursor: "pointer", userSelect: "none" }} {...bind(setSOn, setSDone, sDone)}>
          <HexHold size={80} color="#00FFFF" ms={1500} label="↑" active={sOn} onDone={() => setSDone(true)} />
        </div>
        <div style={{ color: "#6a8a8a", fontSize: 10, marginTop: 4 }}>
          {sDone ? "SENT ✓" : "Hold to send"}
        </div>
        {sDone && <div style={{ color: "#00FFFF", fontSize: 10, marginTop: 4, cursor: "pointer" }} onClick={() => { setSOn(false); setSDone(false); }}>[ reset ]</div>}
      </div>
    </div>
  );
}
