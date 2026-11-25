import * as THREE from 'three';

// ---------------------------------------------------------------------------
// Stinger wipe — the transition overlay (/stinger).
//
// A hot neon blade sweeps in from the lower left and exits through the
// upper right — travelling along the streak angle, like a pulse racing up
// the field: cover (the slab sweeps in) → hold (a static, fully opaque
// navy cover with hatch + seeded streaks — the moment OBS cuts scenes
// underneath) → reveal (the slab sweeps off, trailing glow). The bloom
// look is baked into the shader — layered halos, travelling pulses of
// light on the blade edge — because the scene renders without the bloom
// composer, straight to a transparent canvas, to keep OBS's alpha clean.
//
// The wipe is one full-screen quad; the fragment shader draws the whole
// thing procedurally. Every input is either a constant or a pure function
// of the trigger payload, and the phase comes from absolute time (server
// timestamp + clock offset), so every instance computes the identical
// frame — and the hold is static, so even a stale frame is pixel-identical.
//
// computeStingerPhase(run, nowMs) is pure and exported — the deck reuses
// it to time its scene cut inside the hold window.
//
// createStingerWipe({ accent, bg, aspect, cameraZ, fov, seed })
//   → { group, trigger(run), update(nowMs) → needsRender, resize(aspect), dispose() }
// ---------------------------------------------------------------------------

// Same steep lower-left → upper-right angle as the streak field, so the
// blade's edges read as part of the field's language.
const STREAK_ANGLE = THREE.MathUtils.degToRad(62);

const clamp01 = (v) => Math.min(1, Math.max(0, v));
const easeOutCubic = (p) => 1 - (1 - p) ** 3; // fast in, settles into the hold
const easeInQuad = (p) => p * p; // slow out of the hold, whooshes off

// Where a run is at a given moment. `run.offset` is the server-clock offset
// captured when the trigger arrived (payload.now − local now), so the same
// payload yields the same phase on every machine clock that saw it.
export function computeStingerPhase(run, nowMs) {
  const t = nowMs + (run.offset ?? 0) - run.at;
  const holdEnd = run.coverMs + run.holdMs;
  const end = holdEnd + run.revealMs;
  return {
    t,
    cover: t <= 0 ? 0 : easeOutCubic(clamp01(t / run.coverMs)),
    reveal: t <= holdEnd ? 0 : easeInQuad(clamp01((t - holdEnd) / run.revealMs)),
    holding: t >= run.coverMs && t < holdEnd,
    done: t >= end,
  };
}

const wipeVertexShader = /* glsl */ `
  varying vec2 vLocal;
  void main() {
    vLocal = position.xy;
    gl_Position = projectionMatrix * modelViewMatrix * vec4(position, 1.0);
  }
`;

const wipeFragmentShader = /* glsl */ `
  uniform vec3 uBg;       // cover colour — Namazu navy
  uniform vec3 uAccent;   // blade colour — Namazu hot pink
  uniform float uAngle;   // streak angle — the blade travels along it
  uniform float uCover;   // 0..1 cover front progress (eased)
  uniform float uReveal;  // 0..1 reveal rear progress (eased)
  uniform float uStyle;   // 0 = slash (one fat blade), 1 = sweep (staggered rake)
  uniform float uSeed;
  uniform float uDirection; // +1 = lower-left → upper-right, -1 = reversed
  uniform float uHalfTravel; // plane half-extent along the travel axis
  uniform float uHalfAcross; // plane half-extent along the blade edge
  uniform float uTime;      // seconds since the trigger — drives the edge pulses
  varying vec2 vLocal;

  const vec3 WHITE = vec3(0.95, 0.97, 1.0); // brand near-white

  float hash(float n) { return fract(sin(n) * 43758.5453123); }

  void main() {
    // Travel axis: along the streak direction, so the blade enters at the
    // lower left and exits through the upper right. The blade edge runs
    // across the frame, perpendicular to the streaks.
    vec2 travelDir = vec2(cos(uAngle), sin(uAngle));
    vec2 acrossDir = vec2(-sin(uAngle), cos(uAngle));
    float alongRaw = dot(vLocal, travelDir);
    float acrossRaw = dot(vLocal, acrossDir);
    float travel = alongRaw / uHalfTravel * 0.5 + 0.5; // 0 = lower left, 1 = upper right
    if (uDirection < 0.0) travel = 1.0 - travel;
    float across = acrossRaw / uHalfAcross * 0.5 + 0.5; // position along the edge

    // Edge positions travel through the same space, with overshoot so the
    // blade fully clears the frame at progress 1 (and hides at rest).
    float front = mix(-0.35, 1.35, uCover);
    float rear  = mix(-0.35, 1.35, uReveal);

    // The cover slab sits between the rear and front edges.
    float covered = smoothstep(0.006, -0.006, travel - front)
                  * smoothstep(-0.006, 0.006, travel - rear);

    // Fine hatch on the cover — the same recipe as the streak field.
    float spacing = 0.55;
    float hd = abs(fract(acrossRaw / spacing + 0.5) - 0.5) * spacing;
    float hatch = smoothstep(0.085, 0.03, hd);

    // Static streak bands on the cover — long lines parallel to the travel
    // direction, like the field's streaks. Each carries a soft halo so the
    // cover reads lit rather than flat, and a few get brand-white cores.
    // No time term: during the hold every instance draws identical pixels.
    float streaks = 0.0;
    float streakHalos = 0.0;
    float whiteCores = 0.0;
    for (int i = 0; i < 8; i++) {
      float fi = float(i);
      float r1 = hash(uSeed + fi * 7.31);
      float r2 = hash(uSeed + fi * 3.77 + 11.0);
      float pos = 0.06 + r1 * 0.88;
      float width = 0.004 + r2 * 0.011;
      float dist = abs(across - pos);
      float band = smoothstep(width, 0.0, dist);
      float halo = exp(-dist / (width * 7.0));
      // Gentle static variation along the length so the lines aren't uniform.
      float shape = 0.6 + 0.4 * sin(travel * (2.0 + r1 * 3.0) + r2 * 6.2831);
      float level = (0.22 + 0.6 * r2) * shape;
      streaks += band * level;
      streakHalos += halo * level * 0.4;
      whiteCores += smoothstep(width * 2.4, 0.0, dist) * level * step(0.86, r1);
    }

    // The hot blade: layered pink halos (tight + broad — the bloom look)
    // under a white core whose brightness rides travelling pulses of light,
    // the field's signature. The broad halo spills a soft glow onto the
    // uncovered scene ahead of the blade.
    float dLead = abs(travel - front);
    float leadCore = smoothstep(0.012, 0.0, dLead);
    float leadHalo = exp(-dLead / 0.030) * 0.95 + exp(-dLead / 0.15) * 0.42;

    float pulses = 0.0;
    for (int i = 0; i < 3; i++) {
      float fi = float(i);
      float r1 = hash(uSeed + fi * 5.13 + 3.0);
      float pos = fract(uTime * (0.35 + r1 * 0.5) + r1);
      float d = abs(across - pos);
      d = min(d, 1.0 - d);
      pulses += exp(-d / 0.028) * (0.9 + 0.6 * hash(uSeed + fi * 9.7));
    }

    // Echo lines lag behind the blade (a staggered rake in sweep style).
    float echo1 = exp(-abs(travel - (front - 0.17)) / 0.014);
    float echo2 = exp(-abs(travel - (front - 0.36)) / 0.011);
    float echo = mix(echo1, echo1 * 0.85 + echo2 * 0.85, uStyle);

    // The trailing edge gets the same treatment once the reveal is under
    // way; at rest it sits offscreen, so idle pixels are fully transparent.
    float dRear = abs(travel - rear);
    float rearCore = smoothstep(0.010, 0.0, dRear);
    float rearHalo = exp(-dRear / 0.028) * 0.95 + exp(-dRear / 0.14) * 0.4;

    // Exp halos never reach zero, which would leave a faint tint on screen
    // while idle or holding — so every glow is windowed to the edge it
    // belongs to and cut to exact zero beyond it.
    leadHalo *= smoothstep(0.30, 0.10, dLead);
    rearHalo *= smoothstep(0.30, 0.10, dRear);
    echo *= smoothstep(1.0, 0.9, uCover); // echoes exist only while covering

    // Compose: the slab is opaque navy, lit by hatch, streaks and their
    // halos; the blade rides on top and glows onto the scene either side.
    vec3 slab = uBg
              + uAccent * hatch * 0.10
              + uAccent * streaks * 0.6
              + uAccent * streakHalos * 0.45
              + WHITE * whiteCores * 0.85;
    vec3 blade = uAccent * leadHalo + WHITE * leadCore * (0.7 + pulses) * 1.3;
    vec3 trail = uAccent * rearHalo + WHITE * rearCore * (0.7 + pulses) * 1.15;

    vec3 col = slab * covered + blade + trail + uAccent * echo * 0.55;
    float alpha = covered;
    alpha = max(alpha, clamp(leadHalo, 0.0, 1.0));
    alpha = max(alpha, echo * 0.55);
    alpha = max(alpha, clamp(rearHalo, 0.0, 1.0));
    alpha = clamp(alpha, 0.0, 1.0);

    gl_FragColor = vec4(col, alpha);
  }
`;

export function createStingerWipe({
  accent = new THREE.Color(0xff0099), // Namazu hot pink
  bg = new THREE.Color(0x0d1335), // Namazu navy
  aspect = 16 / 9,
  cameraZ = 14,
  fov = 60,
  seed = 1337, // ?seed= pins the cover layout for every run
} = {}) {
  const group = new THREE.Group();

  const material = new THREE.ShaderMaterial({
    vertexShader: wipeVertexShader,
    fragmentShader: wipeFragmentShader,
    uniforms: {
      uBg: { value: bg },
      uAccent: { value: accent },
      uAngle: { value: STREAK_ANGLE },
      uCover: { value: 0 },
      uReveal: { value: 0 },
      uStyle: { value: 0 },
      uSeed: { value: seed % 4096 }, // small: keeps the hash's sin() precise
      uDirection: { value: 1 },
      uHalfTravel: { value: 1 },
      uHalfAcross: { value: 1 },
      uTime: { value: 0 },
    },
    transparent: true,
    depthWrite: false,
  });

  // A flat plane facing the camera, sized to exactly cover the frustum at
  // z = -6 (the camera stays fixed, like /hidden, so the wipe aligns with
  // the canvas edges whatever sits beneath it). The uniforms normalize the
  // two axes against the visible frustum — the corners of the frame land
  // exactly on travel 0 (lower left) and 1 (upper right).
  function buildPlane(newAspect) {
    const distance = cameraZ + 6;
    const halfH = Math.tan(THREE.MathUtils.degToRad(fov / 2)) * distance;
    const halfW = Math.max(halfH * newAspect, halfH);
    const geometry = new THREE.PlaneGeometry(halfW * 2.1, halfH * 2.1);
    const cosA = Math.abs(Math.cos(STREAK_ANGLE));
    const sinA = Math.abs(Math.sin(STREAK_ANGLE));
    material.uniforms.uHalfTravel.value = cosA * halfW + sinA * halfH;
    material.uniforms.uHalfAcross.value = sinA * halfW + cosA * halfH;
    const mesh = new THREE.Mesh(geometry, material);
    mesh.position.z = -6;
    return mesh;
  }

  let mesh = buildPlane(aspect);
  group.add(mesh);

  let run = null; // active trigger; null = idle

  return {
    group,

    // Start (or restart — latest wins) a run. The payload is the server's
    // trigger plus the locally captured clock offset.
    trigger(next) {
      run = {
        at: next.at,
        offset: next.offset ?? 0,
        coverMs: next.coverMs,
        holdMs: next.holdMs,
        revealMs: next.revealMs,
      };
      material.uniforms.uStyle.value = next.style === 'sweep' ? 1 : 0;
      material.uniforms.uDirection.value = next.direction === -1 ? -1 : 1;
      material.uniforms.uSeed.value = (next.seed ?? seed) % 4096;
    },

    // Advance to `nowMs` (absolute time). Returns false while idle, which
    // the shell reads as "nothing changed — skip the render entirely".
    update(nowMs) {
      if (!run) return false;
      const phase = computeStingerPhase(run, nowMs);
      material.uniforms.uCover.value = phase.cover;
      material.uniforms.uReveal.value = phase.reveal;
      material.uniforms.uTime.value = Math.max(0, phase.t) / 1000; // drives the edge pulses
      if (phase.done) run = null; // this frame renders fully transparent
      return true;
    },

    resize(newAspect) {
      group.remove(mesh);
      mesh.geometry.dispose();
      mesh = buildPlane(newAspect);
      group.add(mesh);
    },

    dispose() {
      mesh.geometry.dispose();
      material.dispose();
      group.clear();
      run = null;
    },
  };
}
