package com.sirdank.skgfx;

import java.nio.FloatBuffer;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Recovers the camera motion between two consecutive frames without ever seeing a view matrix. The game builds each
 * draw's modelview on the CPU as camera x object, so for an object that did not move, {@code MV_B x inverse(MV_A)}
 * equals the camera's own change, whatever the object is. Draws are matched across frames by what they draw (buffers,
 * texture, draw arguments); the delta most matched draws agree on is the camera motion, and draws that disagree
 * (characters, particles) are simply outvoted. Fed by the rewritten matrix, bind and draw calls; render thread only.
 * <p>
 * Matrices are column-major {@code double[16]} like OpenGL's.
 */
final class SkGfxCamera
{
  static final int MODELVIEW = 0x1700;
  static final int PROJECTION = 0x1701;

  private static final int MIN_PAIRS = 12;
  private static final double MIN_SHARE = 0.25;
  private static final double CLUSTER_TOLERANCE = 2e-3;

  /** The camera change between the last two frames, and what a shader needs to reproject between them. */
  record Estimate (double[] delta, int pairs, int agreeing, double[] projection)
  {
    /** True when enough draws agreed for the delta to be trusted. */
    boolean valid ()
    {
      return delta != null;
    }
  }

  private int mode = MODELVIEW;
  private double[] modelview = identity();
  private final ArrayDeque<double[]> modelviewStack = new ArrayDeque<>();
  private int arrayBuffer, elementBuffer, texture;
  /** Frustum of the world pass: l, r, b, t, near, far; null while the current projection is orthographic. */
  private double[] frustum;
  private double[] frameFrustum;
  private boolean worldDone;

  private Map<Long, double[]> current = new HashMap<>();
  private Set<Long> currentDuplicates = new HashSet<>();
  private Map<Long, double[]> previous = new HashMap<>();
  private Set<Long> previousDuplicates = new HashSet<>();
  private Estimate estimate = new Estimate(null, 0, 0, null);

  Estimate estimate ()
  {
    return estimate;
  }

  void matrixMode (int m)
  {
    mode = m;
  }

  void loadIdentity ()
  {
    if (mode == MODELVIEW) modelview = identity();
  }

  void translate (double x, double y, double z)
  {
    if (mode == MODELVIEW) modelview = multiply(modelview, translation(x, y, z));
  }

  void scale (double x, double y, double z)
  {
    if (mode != MODELVIEW) return;
    double[] s = identity();
    s[0] = x;
    s[5] = y;
    s[10] = z;
    modelview = multiply(modelview, s);
  }

  void rotate (double degrees, double x, double y, double z)
  {
    if (mode != MODELVIEW) return;
    double n = Math.sqrt(x * x + y * y + z * z);
    if (n == 0) return;
    x /= n;
    y /= n;
    z /= n;
    double c = Math.cos(Math.toRadians(degrees)), s = Math.sin(Math.toRadians(degrees)), k = 1 - c;
    double[] r = identity();
    r[0] = x * x * k + c;
    r[1] = y * x * k + z * s;
    r[2] = z * x * k - y * s;
    r[4] = x * y * k - z * s;
    r[5] = y * y * k + c;
    r[6] = z * y * k + x * s;
    r[8] = x * z * k + y * s;
    r[9] = y * z * k - x * s;
    r[10] = z * z * k + c;
    modelview = multiply(modelview, r);
  }

  void load (FloatBuffer m)
  {
    if (mode == MODELVIEW) modelview = read(m);
  }

  void multiply (FloatBuffer m)
  {
    if (mode == MODELVIEW) modelview = multiply(modelview, read(m));
  }

  void push ()
  {
    if (mode == MODELVIEW) modelviewStack.push(modelview.clone());
  }

  void pop ()
  {
    if (mode == MODELVIEW && !modelviewStack.isEmpty()) modelview = modelviewStack.pop();
  }

  void perspective (double l, double r, double b, double t, double n, double f)
  {
    if (mode == PROJECTION) frustum = new double[] {l, r, b, t, n, f};
  }

  void orthographic ()
  {
    if (mode == PROJECTION) frustum = null;
  }

  void bindArray (int id)
  {
    arrayBuffer = id;
  }

  void bindElement (int id)
  {
    elementBuffer = id;
  }

  void bindTexture (int id)
  {
    texture = id;
  }

  /** A draw call in the world pass: remembers its modelview under what identifies it. */
  void draw (int primitive, int start, int end, int count, int type, long offset)
  {
    if (worldDone) return;
    if (frameFrustum == null && frustum != null) frameFrustum = frustum;
    long key = 17;
    for (long v : new long[] {arrayBuffer, elementBuffer, texture, primitive, start, end, count, type, offset}) {
      key = key * 0x9E3779B97F4A7C15L + v + 0x7F4A7C15L;
      key ^= key >>> 29;
    }
    if (current.put(key, modelview) != null) currentDuplicates.add(key);
  }

  /** The 3D world is drawn; later (HUD) draws are not part of the camera estimate. */
  void endWorld ()
  {
    worldDone = true;
  }

  /** The frame is over: estimate the camera change since the previous frame, and start collecting the next. */
  void finishFrame ()
  {
    estimate = compute(previous, previousDuplicates, current, currentDuplicates, frameFrustum);
    previous = current;
    previousDuplicates = currentDuplicates;
    current = new HashMap<>();
    currentDuplicates = new HashSet<>();
    frameFrustum = null;
    worldDone = false;
    modelviewStack.clear();
  }

  static Estimate compute (Map<Long, double[]> a, Set<Long> aDuplicates, Map<Long, double[]> b, Set<Long> bDuplicates,
    double[] frustum)
  {
    java.util.List<double[]> deltas = new java.util.ArrayList<>();
    for (Map.Entry<Long, double[]> e : b.entrySet()) {
      long key = e.getKey();
      double[] before = a.get(key);
      if (before == null || aDuplicates.contains(key) || bDuplicates.contains(key)) continue;
      double[] inverse = affineInverse(before);
      if (inverse != null) deltas.add(multiply(e.getValue(), inverse));
    }
    if (deltas.size() < MIN_PAIRS || frustum == null) return new Estimate(null, deltas.size(), 0, frustum);
    // Vote: the delta the most pairs agree with (within tolerance) is the camera.
    double[] best = null;
    int bestCount = 0;
    int step = Math.max(1, deltas.size() / 256);
    for (int i = 0; i < deltas.size(); i += step) {
      int count = 0;
      for (double[] d : deltas) if (close(deltas.get(i), d)) count++;
      if (count > bestCount) {
        bestCount = count;
        best = deltas.get(i);
      }
    }
    if (best == null || bestCount < MIN_PAIRS || bestCount < MIN_SHARE * deltas.size()) {
      return new Estimate(null, deltas.size(), bestCount, frustum);
    }
    double[] mean = new double[16];
    int n = 0;
    for (double[] d : deltas) {
      if (!close(best, d)) continue;
      for (int i = 0; i < 16; i++) mean[i] += d[i];
      n++;
    }
    for (int i = 0; i < 16; i++) mean[i] /= n;
    return new Estimate(mean, deltas.size(), n, frustum);
  }

  private static boolean close (double[] x, double[] y)
  {
    for (int i = 0; i < 16; i++) if (Math.abs(x[i] - y[i]) > CLUSTER_TOLERANCE) return false;
    return true;
  }

  // ---- matrix helpers (column-major)

  static double[] identity ()
  {
    return new double[] {1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1};
  }

  static double[] translation (double x, double y, double z)
  {
    double[] t = identity();
    t[12] = x;
    t[13] = y;
    t[14] = z;
    return t;
  }

  private static double[] read (FloatBuffer m)
  {
    double[] out = new double[16];
    for (int i = 0; i < 16; i++) out[i] = m.get(m.position() + i);
    return out;
  }

  static double[] multiply (double[] a, double[] b)
  {
    double[] r = new double[16];
    for (int col = 0; col < 4; col++) {
      for (int row = 0; row < 4; row++) {
        double sum = 0;
        for (int k = 0; k < 4; k++) sum += a[k * 4 + row] * b[col * 4 + k];
        r[col * 4 + row] = sum;
      }
    }
    return r;
  }

  /** Inverse of an affine matrix (last row 0 0 0 1); null when singular. */
  static double[] affineInverse (double[] m)
  {
    double a = m[0], b = m[4], c = m[8], d = m[1], e = m[5], f = m[9], g = m[2], h = m[6], i = m[10];
    double det = a * (e * i - f * h) - b * (d * i - f * g) + c * (d * h - e * g);
    if (Math.abs(det) < 1e-12) return null;
    double[] r = identity();
    r[0] = (e * i - f * h) / det;
    r[4] = -(b * i - c * h) / det;
    r[8] = (b * f - c * e) / det;
    r[1] = -(d * i - f * g) / det;
    r[5] = (a * i - c * g) / det;
    r[9] = -(a * f - c * d) / det;
    r[2] = (d * h - e * g) / det;
    r[6] = -(a * h - b * g) / det;
    r[10] = (a * e - b * d) / det;
    double tx = m[12], ty = m[13], tz = m[14];
    r[12] = -(r[0] * tx + r[4] * ty + r[8] * tz);
    r[13] = -(r[1] * tx + r[5] * ty + r[9] * tz);
    r[14] = -(r[2] * tx + r[6] * ty + r[10] * tz);
    return r;
  }

  /**
   * The camera {@code s} of the way along its motion ({@code delta} to the power {@code s}): 0 is no motion, 1 is the
   * whole step, 0.5 the halfway pose, so {@code step(d, s) x step(d, 1 - s) = d}. Null when {@code delta} is not close to
   * rigid (scale or shear) or turns by half a turn, in which case no in-between pose is trusted.
   */
  static double[] step (double[] delta, double s)
  {
    // Rotation part must be orthonormal.
    double[] r = delta;
    for (int c = 0; c < 3; c++) {
      for (int k = c; k < 3; k++) {
        double dot = r[c * 4] * r[k * 4] + r[c * 4 + 1] * r[k * 4 + 1] + r[c * 4 + 2] * r[k * 4 + 2];
        if (Math.abs(dot - (c == k ? 1 : 0)) > 0.02) return null;
      }
    }
    double trace = r[0] + r[5] + r[10];
    double angle = Math.acos(Math.max(-1, Math.min(1, (trace - 1) / 2)));
    double tx = delta[12], ty = delta[13], tz = delta[14];
    double[] out = identity();
    if (angle < 1e-9) { // a pure translation
      out[12] = s * tx;
      out[13] = s * ty;
      out[14] = s * tz;
      return out;
    }
    double ax = r[6] - r[9], ay = r[8] - r[2], az = r[1] - r[4];
    double n = Math.sqrt(ax * ax + ay * ay + az * az);
    if (n < 1e-9) return null; // 180 degrees: the axis is undefined, not a camera step
    ax /= n;
    ay /= n;
    az /= n;
    double phi = angle * s;
    double c = Math.cos(phi), sn = Math.sin(phi), k = 1 - c;
    out[0] = ax * ax * k + c;
    out[1] = ay * ax * k + az * sn;
    out[2] = az * ax * k - ay * sn;
    out[4] = ax * ay * k - az * sn;
    out[5] = ay * ay * k + c;
    out[6] = az * ay * k + ax * sn;
    out[8] = ax * az * k + ay * sn;
    out[9] = ay * az * k - ax * sn;
    out[10] = az * az * k + c;
    // Translation of delta^s: along the rotation axis it grows linearly, across it the geometric series
    // (1 - e^(i s theta)) / (1 - e^(i theta)) turns and scales the perpendicular part.
    double along = tx * ax + ty * ay + tz * az;
    double px = tx - along * ax, py = ty - along * ay, pz = tz - along * az;
    double cosT = Math.cos(angle), sinT = Math.sin(angle);
    double denominator = 2 * (1 - cosT);
    double cr = ((1 - c) * (1 - cosT) + sn * sinT) / denominator;
    double ci = ((1 - c) * sinT - sn * (1 - cosT)) / denominator;
    // a x p turns the perpendicular part a quarter turn about the axis.
    double qx = ay * pz - az * py, qy = az * px - ax * pz, qz = ax * py - ay * px;
    out[12] = s * along * ax + cr * px + ci * qx;
    out[13] = s * along * ay + cr * py + ci * qy;
    out[14] = s * along * az + cr * pz + ci * qz;
    return out;
  }

  /** The camera halfway along its motion; {@code halfStep(d) x halfStep(d) = d}. */
  static double[] halfStep (double[] delta)
  {
    return step(delta, 0.5);
  }
}
