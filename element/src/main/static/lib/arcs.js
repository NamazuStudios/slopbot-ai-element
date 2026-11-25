import * as THREE from 'three';

// ---------------------------------------------------------------------------
// Deterministic RNG (mulberry32). The scenes share one default seed, so
// every scene and reload renders the exact same field.
// ---------------------------------------------------------------------------
function mulberry32(seed) {
  let a = seed >>> 0;
  return function () {
    a |= 0;
    a = (a + 0x6d2b79f5) | 0;
    let t = Math.imul(a ^ (a >>> 15), 1 | a);
    t = (t + Math.imul(t ^ (t >>> 7), 61 | t)) ^ t;
    return ((t ^ (t >>> 14)) >>> 0) / 4294967296;
  };
}

// ---------------------------------------------------------------------------
// Concentric arc field — the visual language of the /hidden overlay.
//
// Arcs ring a common centre at varying radii, each spanning 30–120°, thin
// or thick, spinning about the centre at its own rate and direction. Each
// arc is a soft neon band with a hot coreline (a few render brand-white),
// faded at both angular ends. The field renders on a transparent canvas
// with no bloom so OBS can composite it over other sources.
//
// createArcField({ accent, count, speed, seed })
//   → { group, update(delta, elapsed), dispose() }
// ---------------------------------------------------------------------------

const arcVertexShader = /* glsl */ `
  varying vec2 vLocal;
  void main() {
    vLocal = position.xy;
    gl_Position = projectionMatrix * modelViewMatrix * vec4(position, 1.0);
  }
`;

const arcFragmentShader = /* glsl */ `
  uniform vec3 uBody;
  uniform vec3 uCore;
  uniform float uInner;
  uniform float uOuter;
  uniform float uThetaLength;
  uniform float uIntensity;
  uniform float uCoreWidth;
  varying vec2 vLocal;

  const float TAU = 6.28318530718;

  void main() {
    // Radial profile across the arc's thickness (0 at inner edge, 1 at outer).
    float r = length(vLocal);
    float radial = clamp((r - uInner) / (uOuter - uInner), 0.0, 1.0);
    float edge = min(radial, 1.0 - radial) * 2.0;     // 0 at both edges
    float body = smoothstep(0.0, 0.55, edge);         // soft neon falloff
    float core = smoothstep(uCoreWidth, 0.0, abs(radial - 0.5) * 2.0);

    // Angular fade at the arc's ends — computed in local space, so it stays
    // glued to the arc as the mesh spins.
    float theta = atan(vLocal.y, vLocal.x);
    float a = mod(theta + TAU, TAU);
    float ends = smoothstep(0.0, 0.12, a) * smoothstep(0.0, 0.12, uThetaLength - a);

    float level = body * ends * uIntensity;
    vec3 col = uBody * level + uCore * core * level * 1.35;
    float alpha = clamp(level * (1.0 + core * 0.6), 0.0, 1.0);
    gl_FragColor = vec4(col, alpha);
  }
`;

export function createArcField({
  accent = new THREE.Color(0xff0099), // Namazu hot pink
  count = 16,
  speed = 1,          // merged pacing (scene default × ?speed=)
  seed = 1337,        // shared default → identical field on every scene
} = {}) {
  const group = new THREE.Group();
  const arcs = [];
  const random = mulberry32(seed);

  const baseHsl = {};
  accent.getHSL(baseHsl);

  const disposables = [];

  for (let i = 0; i < count; i++) {
    // Concentric rings from the centre outward, with a little jitter.
    const inner = 1.4 + ((i + 0.5) / count) * 7.8 + (random() - 0.5) * 0.6;

    // Thin and thick arcs ("the arcs may be thick").
    const thick = random() < 0.35;
    const thickness = thick ? 0.5 + random() * 0.6 : 0.08 + random() * 0.2;
    const outer = inner + thickness;

    // 30–120° spans.
    const span = 0.524 + random() * 1.571;

    const geometry = new THREE.RingGeometry(
      inner,
      outer,
      Math.max(6, Math.ceil((span / (Math.PI * 2)) * 96)),
      1,
      0,
      span,
    );

    // Brand palette: soft pink bodies with hot corelines, a few white arcs.
    const hue = ((((baseHsl.h * 360 + (random() - 0.5) * 40) % 360) + 360) % 360) / 360;
    const whiteArc = random() < 0.12;
    const body = new THREE.Color().setHSL(hue, whiteArc ? 0.12 : 0.85, whiteArc ? 0.85 : 0.5);
    const core = new THREE.Color().setHSL(hue, whiteArc ? 0.06 : 0.55, 0.92);

    const hot = random() < 0.15;
    const intensity = hot ? 1.0 + random() * 0.3 : 0.35 + random() * 0.55;

    const material = new THREE.ShaderMaterial({
      vertexShader: arcVertexShader,
      fragmentShader: arcFragmentShader,
      uniforms: {
        uBody: { value: body },
        uCore: { value: core },
        uInner: { value: inner },
        uOuter: { value: outer },
        uThetaLength: { value: span },
        uIntensity: { value: intensity },
        uCoreWidth: { value: thick ? 0.22 : 0.55 },
      },
      transparent: true,
      depthWrite: false,
    });

    const mesh = new THREE.Mesh(geometry, material);
    mesh.rotation.z = random() * Math.PI * 2;
    mesh.position.z = (random() - 0.5) * 2;

    // Each arc spins about the centre at its own rate and direction,
    // with a gentle brightness breathing.
    arcs.push({
      mesh,
      base: mesh.rotation.z,
      rate: (0.03 + random() * 0.3) * (random() < 0.5 ? -1 : 1),
      intensity,
      breathRate: 0.1 + random() * 0.4,
      breathPhase: random() * Math.PI * 2,
    });

    disposables.push(geometry, material);
    group.add(mesh);
  }

  function update(delta, elapsed) {
    void delta; // rotation is a pure function of time
    const t = elapsed * speed;
    for (const arc of arcs) {
      arc.mesh.rotation.z = arc.base + t * arc.rate;
      arc.mesh.material.uniforms.uIntensity.value =
        arc.intensity * (0.85 + 0.15 * Math.sin(t * arc.breathRate + arc.breathPhase));
    }
  }

  function dispose() {
    for (const item of disposables) item.dispose();
    disposables.length = 0;
    group.clear();
  }

  return { group, update, dispose };
}
