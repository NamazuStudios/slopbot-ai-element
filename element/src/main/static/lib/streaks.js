import * as THREE from 'three';

// ---------------------------------------------------------------------------
// Deterministic RNG (mulberry32). The scenes share one default seed, so
// /background, /starting-soon and /brb render the exact same field — only
// their titles differ — and every reload shows the same streaks.
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
// Neon streak field — the shared visual language of the scenes.
//
// Long soft light streaks sweep from lower left to upper right at a steep,
// mostly parallel angle — each a halo-led accent glow (the neon/laser look,
// no thin white cores) with pulses of light travelling along it. A couple of
// cross-cutting streaks strike through at different angles — additive light
// sums where they cross, so the intersections glow as Xs. Streaks sit at
// different depths (layered parallax), two broad soft bands bracket a darker
// diagonal channel kept clear for the title, and a fine tight diagonal hatch
// textures the navy background.
//
// Positions are static; the motion is light sweeping along the streaks.
// Everything is placed from the seeded RNG, so all scenes render the exact
// same field.
//
// createStreakField({ accent, count, speed, brightness, aspect, cameraZ, fov, seed })
//   → { group, update(delta, elapsed), dispose(), resize(aspect) }
// ---------------------------------------------------------------------------

// Steep lower-left → upper-right angle.
const STREAK_ANGLE = THREE.MathUtils.degToRad(62);

const streakVertexShader = /* glsl */ `
  varying vec2 vUv;
  void main() {
    vUv = uv;
    gl_Position = projectionMatrix * modelViewMatrix * vec4(position, 1.0);
  }
`;

const streakFragmentShader = /* glsl */ `
  uniform vec3 uCore;
  uniform vec3 uHalo;
  uniform float uIntensity;
  uniform float uSharp;
  uniform float uPulseSpeed;
  uniform float uPhase;
  uniform float uTime;
  varying vec2 vUv;

  void main() {
    // Cross-section: 0 at the centre line, 1 at the quad edge.
    float across = abs(vUv.y - 0.5) * 2.0;
    float coreWidth = mix(0.30, 0.07, uSharp);   // crisp streaks: tighter core
    // Only crisp foreground streaks carry the white-hot core — blurry
    // streaks and the bands stay halo-led so the pink never washes to white.
    float core = smoothstep(coreWidth, 0.0, across) * smoothstep(0.0, 0.4, uSharp);
    float halo = pow(max(1.0 - across, 0.0), mix(1.4, 3.2, uSharp));

    // Fade the ends so streaks dissolve instead of ending abruptly.
    float ends = smoothstep(0.0, 0.12, vUv.x) * smoothstep(1.0, 0.88, vUv.x);

    // A pulse of light sweeping along the streak (lower-left → upper-right).
    float pos = fract(uTime * uPulseSpeed + uPhase);
    float d = min(abs(vUv.x - pos), 1.0 - abs(vUv.x - pos));
    float pulse = smoothstep(0.22, 0.0, d);

    // Uneven brightness: resting level plus the travelling highlight.
    float level = uIntensity * (0.4 + 0.9 * pulse);

    // Soft elements lean on the halo (pink-led); crisp ones on the core.
    float haloAlpha = mix(0.7, 0.4, uSharp);
    vec3 col = uCore * core * 1.5 + uHalo * halo * 0.5;
    float alpha = (core + halo * haloAlpha) * ends * level;
    gl_FragColor = vec4(col, alpha);
  }
`;

const hatchVertexShader = /* glsl */ `
  varying vec2 vLocal;
  void main() {
    vLocal = position.xy;
    gl_Position = projectionMatrix * modelViewMatrix * vec4(position, 1.0);
  }
`;

const hatchFragmentShader = /* glsl */ `
  uniform float uAngle;
  uniform float uSpacing;
  uniform vec3 uColor;
  uniform float uOpacity;
  varying vec2 vLocal;
  void main() {
    // Perpendicular distance to the family of diagonal lines.
    float s = sin(uAngle);
    float c = cos(uAngle);
    float perp = -vLocal.x * s + vLocal.y * c;
    float d = abs(fract(perp / uSpacing + 0.5) - 0.5) * uSpacing;
    float line = smoothstep(0.085, 0.03, d);
    gl_FragColor = vec4(uColor * line * uOpacity, 1.0);
  }
`;

export function createStreakField({
  accent = new THREE.Color(0xff0099), // Namazu hot pink
  count = 14,
  speed = 1,          // merged pacing (scene default × ?speed=)
  brightness = 1,
  aspect = 16 / 9,
  cameraZ = 14,
  fov = 60,
  seed = 1337,        // shared default → identical field across scenes
} = {}) {
  const group = new THREE.Group();
  const random = mulberry32(seed);
  const timeUniform = { value: 0 }; // shared by every streak — one update drives all

  const WHITE = new THREE.Color(0xffffff); // brand white accents

  const halfTan = Math.tan(THREE.MathUtils.degToRad(fov / 2));
  const halfH = halfTan * cameraZ;               // frame half-height at z = 0
  const halfW = Math.max(halfH * aspect, halfH); // frame half-width
  const diagonal = Math.hypot(halfW, halfH);

  const disposables = [];

  // --- background: fine diagonal hatch, echoing the streaks -----------------
  function buildHatch(hatchAspect) {
    const distance = cameraZ + 14;               // the plane sits at z = -14
    const planeHalfH = halfTan * distance + 1.5; // margin for camera sway
    const planeHalfW = planeHalfH * hatchAspect + 1.5;
    const geometry = new THREE.PlaneGeometry(planeHalfW * 2, planeHalfH * 2);
    const material = new THREE.ShaderMaterial({
      vertexShader: hatchVertexShader,
      fragmentShader: hatchFragmentShader,
      uniforms: {
        uAngle: { value: STREAK_ANGLE },
        uSpacing: { value: 0.55 },
        uColor: { value: accent.clone() },
        uOpacity: { value: 0.06 * brightness },
      },
      transparent: true,
      blending: THREE.AdditiveBlending,
      depthWrite: false,
    });
    const mesh = new THREE.Mesh(geometry, material);
    mesh.position.z = -14;
    return mesh;
  }

  let hatch = buildHatch(aspect);
  group.add(hatch);

  // --- one streak ------------------------------------------------------------
  function addStreak({ angle, offset, width, sharp, intensity, z }) {
    const geometry = new THREE.PlaneGeometry(diagonal * 2.3 + random() * 8, width);

    // Slight hue variation in the halos so the field isn't monochrome.
    const hsl = {};
    accent.getHSL(hsl);
    const hue = ((((hsl.h * 360 + (random() - 0.5) * 24) % 360) + 360) % 360) / 360;
    const halo = new THREE.Color().setHSL(hue, Math.min(1, hsl.s + 0.15), hsl.l);

    const material = new THREE.ShaderMaterial({
      vertexShader: streakVertexShader,
      fragmentShader: streakFragmentShader,
      uniforms: {
        uCore: { value: WHITE },
        uHalo: { value: halo },
        uIntensity: { value: intensity * brightness },
        uSharp: { value: sharp },
        uPulseSpeed: { value: (0.04 + random() * 0.08) * speed },
        uPhase: { value: random() },
        uTime: timeUniform,
      },
      transparent: true,
      blending: THREE.AdditiveBlending,
      depthWrite: false,
    });

    const p = new THREE.Vector2(-Math.sin(angle), Math.cos(angle)); // perpendicular
    const mesh = new THREE.Mesh(geometry, material);
    mesh.position.set(p.x * offset, p.y * offset, z);
    mesh.rotation.z = angle;

    disposables.push(geometry, material);
    group.add(mesh);
    return mesh;
  }

  // --- main streaks: steep, mostly parallel, soft halo-led -------------------
  // A darker diagonal channel through the centre stays clear for the title;
  // streaks live on either side of it. All soft pink — no thin white cores.
  // (Crisp white-core streaks can return by giving a streak `sharp` > 0.4.)
  const channelHalf = 5.5;
  for (let i = 0; i < count; i++) {
    const side = random() < 0.5 ? -1 : 1;
    const offset = side * (channelHalf + 1.5 + random() * (diagonal * 0.95 - channelHalf - 1.5));
    addStreak({
      angle: STREAK_ANGLE + (random() - 0.5) * 0.12,
      offset,
      width: 0.35 + random() * 0.45,
      sharp: random() * 0.3,
      intensity: 0.35 + random() * 0.4,
      z: -6 + random() * 5,
    });
  }

  // --- cross-cutting streaks: different angles → glowing X intersections ----
  addStreak({
    angle: STREAK_ANGLE - 0.62, // shallow cut through the upper left
    offset: 7,
    width: 0.5,
    sharp: 0.2,
    intensity: 0.9,
    z: -2,
  });
  addStreak({
    angle: STREAK_ANGLE + 0.5, // near vertical, down the right edge
    offset: -10,
    width: 0.45,
    sharp: 0.2,
    intensity: 0.95,
    z: -2,
  });

  // --- two broad soft bands bracketing the title channel --------------------
  for (const side of [-1, 1]) {
    addStreak({
      angle: STREAK_ANGLE + (random() - 0.5) * 0.05,
      offset: side * (channelHalf + 3.2),
      width: 6.5,
      sharp: 0,
      intensity: 0.75 + random() * 0.2,
      z: -4,
    });
  }

  function update(delta, elapsed) {
    void delta; // streaks are static; the motion is light sweeping along them
    timeUniform.value = elapsed;
  }

  function resize(newAspect) {
    group.remove(hatch);
    hatch.geometry.dispose();
    hatch.material.dispose();
    hatch = buildHatch(newAspect);
    group.add(hatch);
  }

  function dispose() {
    for (const item of disposables) item.dispose();
    disposables.length = 0;
    hatch.geometry.dispose();
    hatch.material.dispose();
    group.clear();
  }

  return { group, update, dispose, resize };
}
