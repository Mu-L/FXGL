/*
 * FXGL - JavaFX Game Library. The MIT License (MIT).
 * Copyright (c) AlmasB (almaslvl@gmail.com).
 * See LICENSE for details.
 */
package com.almasb.fxgl.physics.box2d.collision;

import com.almasb.fxgl.core.math.Vec2;
import com.almasb.fxgl.physics.box2d.common.JBoxSettings;
import com.almasb.fxgl.physics.box2d.common.Rotation;
import com.almasb.fxgl.physics.box2d.common.Transform;

/**
 * This is non-static for faster pooling. To get an instance, use {@link com.almasb.fxgl.physics.box2d.pooling.IWorldPool}, don't
 * construct a distance object.
 *
 * @author Daniel Murphy
 */
public class Distance {
    private static final int MAX_ITERS = 20;

    /**
     * GJK using Voronoi regions (Christer Ericson) and Barycentric coordinates.
     */
    private class SimplexVertex {
        public final Vec2 wA = new Vec2(); // support point in shapeA
        public final Vec2 wB = new Vec2(); // support point in shapeB
        public final Vec2 w = new Vec2(); // wB - wA
        public float a; // barycentric coordinate for closest point
        public int indexA; // wA index
        public int indexB; // wB index

        public void set(SimplexVertex sv) {
            wA.set(sv.wA);
            wB.set(sv.wB);
            w.set(sv.w);
            a = sv.a;
            indexA = sv.indexA;
            indexB = sv.indexB;
        }
    }

    /**
     * Used to warm start Distance. Set count to zero on first call.
     *
     * @author daniel
     */
    static class SimplexCache {
        /** length or area */
        private float metric = 0f;
        public int count = 0;

        /** vertices on shape A */
        public final int[] indexA = new int[3];
        /** vertices on shape B */
        public final int[] indexB = new int[3];

        public SimplexCache() {
            indexA[0] = Integer.MAX_VALUE;
            indexA[1] = Integer.MAX_VALUE;
            indexA[2] = Integer.MAX_VALUE;
            indexB[0] = Integer.MAX_VALUE;
            indexB[1] = Integer.MAX_VALUE;
            indexB[2] = Integer.MAX_VALUE;
        }
    }

    private class Simplex {
        public final SimplexVertex m_v1 = new SimplexVertex();
        public final SimplexVertex m_v2 = new SimplexVertex();
        public final SimplexVertex m_v3 = new SimplexVertex();
        public final SimplexVertex vertices[] = {m_v1, m_v2, m_v3};
        public int m_count;

        public void readCache(SimplexCache cache,
                              DistanceProxy proxyA, Transform transformA,
                              DistanceProxy proxyB, Transform transformB) {

            // Copy data from cache.
            m_count = cache.count;

            for (int i = 0; i < m_count; ++i) {
                SimplexVertex v = vertices[i];
                v.indexA = cache.indexA[i];
                v.indexB = cache.indexB[i];
                Vec2 wALocal = proxyA.getVertex(v.indexA);
                Vec2 wBLocal = proxyB.getVertex(v.indexB);
                Transform.mulToOutUnsafe(transformA, wALocal, v.wA);
                Transform.mulToOutUnsafe(transformB, wBLocal, v.wB);
                v.w.set(v.wB).subLocal(v.wA);
                v.a = 0.0f;
            }

            // Compute the new simplex metric, if it is substantially different than
            // old metric then flush the simplex.
            if (m_count > 1) {
                float metric1 = cache.metric;
                float metric2 = getMetric();
                if (metric2 < 0.5f * metric1 || 2.0f * metric1 < metric2 || metric2 < JBoxSettings.EPSILON) {
                    // Reset the simplex.
                    m_count = 0;
                }
            }

            // If the cache is empty or invalid ...
            if (m_count == 0) {
                SimplexVertex v = vertices[0];
                v.indexA = 0;
                v.indexB = 0;
                Vec2 wALocal = proxyA.getVertex(0);
                Vec2 wBLocal = proxyB.getVertex(0);
                Transform.mulToOutUnsafe(transformA, wALocal, v.wA);
                Transform.mulToOutUnsafe(transformB, wBLocal, v.wB);
                v.w.set(v.wB).subLocal(v.wA);
                m_count = 1;
            }
        }

        public void writeCache(SimplexCache cache) {
            cache.metric = getMetric();
            cache.count = m_count;

            for (int i = 0; i < m_count; ++i) {
                cache.indexA[i] = vertices[i].indexA;
                cache.indexB[i] = vertices[i].indexB;
            }
        }

        private final Vec2 e12 = new Vec2();

        public final void getSearchDirection(final Vec2 out) {
            switch (m_count) {
                case 1:
                    out.set(m_v1.w).negateLocal();
                    return;
                case 2:
                    e12.set(m_v2.w).subLocal(m_v1.w);
                    // use out for a temp variable real quick
                    out.set(m_v1.w).negateLocal();
                    float sgn = Vec2.cross(e12, out);

                    if (sgn > 0f) {
                        // Origin is left of e12.
                        Vec2.crossToOutUnsafe(1f, e12, out);
                        return;
                    } else {
                        // Origin is right of e12.
                        Vec2.crossToOutUnsafe(e12, 1f, out);
                        return;
                    }
                default:
                    assert false;
                    out.setZero();
            }
        }

        // djm pooled
        private final Vec2 case2 = new Vec2();
        private final Vec2 case22 = new Vec2();

        /**
         * This returns pooled objects. don't keep or modify them
         */
        public void getClosestPoint(final Vec2 out) {
            switch (m_count) {
                case 0:
                    assert false;
                    out.setZero();
                    return;
                case 1:
                    out.set(m_v1.w);
                    return;
                case 2:
                    case22.set(m_v2.w).mulLocal(m_v2.a);
                    case2.set(m_v1.w).mulLocal(m_v1.a).addLocal(case22);
                    out.set(case2);
                    return;
                case 3:
                    out.setZero();
                    return;
                default:
                    assert false;
                    out.setZero();
            }
        }

        // djm pooled, and from above
        private final Vec2 case3 = new Vec2();
        private final Vec2 case33 = new Vec2();

        public void getWitnessPoints(Vec2 pA, Vec2 pB) {
            switch (m_count) {
                case 0:
                    assert false;
                    break;

                case 1:
                    pA.set(m_v1.wA);
                    pB.set(m_v1.wB);
                    break;

                case 2:
                    case2.set(m_v1.wA).mulLocal(m_v1.a);
                    pA.set(m_v2.wA).mulLocal(m_v2.a).addLocal(case2);
                    // m_v1.a * m_v1.wA + m_v2.a * m_v2.wA;
                    // *pB = m_v1.a * m_v1.wB + m_v2.a * m_v2.wB;
                    case2.set(m_v1.wB).mulLocal(m_v1.a);
                    pB.set(m_v2.wB).mulLocal(m_v2.a).addLocal(case2);

                    break;

                case 3:
                    pA.set(m_v1.wA).mulLocal(m_v1.a);
                    case3.set(m_v2.wA).mulLocal(m_v2.a);
                    case33.set(m_v3.wA).mulLocal(m_v3.a);
                    pA.addLocal(case3).addLocal(case33);
                    pB.set(pA);
                    // *pA = m_v1.a * m_v1.wA + m_v2.a * m_v2.wA + m_v3.a * m_v3.wA;
                    // *pB = *pA;
                    break;

                default:
                    assert false;
                    break;
            }
        }

        // djm pooled, from above
        public float getMetric() {
            switch (m_count) {
                case 0:
                    assert false;
                    return 0.0f;

                case 1:
                    return 0.0f;

                case 2:
                    return m_v1.w.distanceF(m_v2.w);

                case 3:
                    case3.set(m_v2.w).subLocal(m_v1.w);
                    case33.set(m_v3.w).subLocal(m_v1.w);
                    // return Vec2.cross(m_v2.w - m_v1.w, m_v3.w - m_v1.w);
                    return Vec2.cross(case3, case33);

                default:
                    assert false;
                    return 0.0f;
            }
        }

        // djm pooled from above

        /**
         * Solve a line segment using barycentric coordinates.
         */
        public void solve2() {
            // Solve a line segment using barycentric coordinates.
            //
            // p = a1 * w1 + a2 * w2
            // a1 + a2 = 1
            //
            // The vector from the origin to the closest point on the line is
            // perpendicular to the line.
            // e12 = w2 - w1
            // dot(p, e) = 0
            // a1 * dot(w1, e) + a2 * dot(w2, e) = 0
            //
            // 2-by-2 linear system
            // [1 1 ][a1] = [1]
            // [w1.e12 w2.e12][a2] = [0]
            //
            // Define
            // d12_1 = dot(w2, e12)
            // d12_2 = -dot(w1, e12)
            // d12 = d12_1 + d12_2
            //
            // Solution
            // a1 = d12_1 / d12
            // a2 = d12_2 / d12
            final Vec2 w1 = m_v1.w;
            final Vec2 w2 = m_v2.w;
            e12.set(w2).subLocal(w1);

            // w1 region
            float d12_2 = -Vec2.dot(w1, e12);
            if (d12_2 <= 0.0f) {
                // a2 <= 0, so we clamp it to 0
                m_v1.a = 1.0f;
                m_count = 1;
                return;
            }

            // w2 region
            float d12_1 = Vec2.dot(w2, e12);
            if (d12_1 <= 0.0f) {
                // a1 <= 0, so we clamp it to 0
                m_v2.a = 1.0f;
                m_count = 1;
                m_v1.set(m_v2);
                return;
            }

            // Must be in e12 region.
            float inv_d12 = 1.0f / (d12_1 + d12_2);
            m_v1.a = d12_1 * inv_d12;
            m_v2.a = d12_2 * inv_d12;
            m_count = 2;
        }

        // djm pooled, and from above
        private final Vec2 e13 = new Vec2();
        private final Vec2 e23 = new Vec2();
        private final Vec2 w1 = new Vec2();
        private final Vec2 w2 = new Vec2();
        private final Vec2 w3 = new Vec2();

        /**
         * Solve a line segment using barycentric coordinates.<br/>
         * Possible regions:<br/>
         * - points[2]<br/>
         * - edge points[0]-points[2]<br/>
         * - edge points[1]-points[2]<br/>
         * - inside the triangle
         */
        public void solve3() {
            w1.set(m_v1.w);
            w2.set(m_v2.w);
            w3.set(m_v3.w);

            // Edge12
            // [1 1 ][a1] = [1]
            // [w1.e12 w2.e12][a2] = [0]
            // a3 = 0
            e12.set(w2).subLocal(w1);
            float w1e12 = Vec2.dot(w1, e12);
            float w2e12 = Vec2.dot(w2, e12);
            float d12_1 = w2e12;
            float d12_2 = -w1e12;

            // Edge13
            // [1 1 ][a1] = [1]
            // [w1.e13 w3.e13][a3] = [0]
            // a2 = 0
            e13.set(w3).subLocal(w1);
            float w1e13 = Vec2.dot(w1, e13);
            float w3e13 = Vec2.dot(w3, e13);
            float d13_1 = w3e13;
            float d13_2 = -w1e13;

            // Edge23
            // [1 1 ][a2] = [1]
            // [w2.e23 w3.e23][a3] = [0]
            // a1 = 0
            e23.set(w3).subLocal(w2);
            float w2e23 = Vec2.dot(w2, e23);
            float w3e23 = Vec2.dot(w3, e23);
            float d23_1 = w3e23;
            float d23_2 = -w2e23;

            // Triangle123
            float n123 = Vec2.cross(e12, e13);

            float d123_1 = n123 * Vec2.cross(w2, w3);
            float d123_2 = n123 * Vec2.cross(w3, w1);
            float d123_3 = n123 * Vec2.cross(w1, w2);

            // w1 region
            if (d12_2 <= 0.0f && d13_2 <= 0.0f) {
                m_v1.a = 1.0f;
                m_count = 1;
                return;
            }

            // e12
            if (d12_1 > 0.0f && d12_2 > 0.0f && d123_3 <= 0.0f) {
                float inv_d12 = 1.0f / (d12_1 + d12_2);
                m_v1.a = d12_1 * inv_d12;
                m_v2.a = d12_2 * inv_d12;
                m_count = 2;
                return;
            }

            // e13
            if (d13_1 > 0.0f && d13_2 > 0.0f && d123_2 <= 0.0f) {
                float inv_d13 = 1.0f / (d13_1 + d13_2);
                m_v1.a = d13_1 * inv_d13;
                m_v3.a = d13_2 * inv_d13;
                m_count = 2;
                m_v2.set(m_v3);
                return;
            }

            // w2 region
            if (d12_1 <= 0.0f && d23_2 <= 0.0f) {
                m_v2.a = 1.0f;
                m_count = 1;
                m_v1.set(m_v2);
                return;
            }

            // w3 region
            if (d13_1 <= 0.0f && d23_1 <= 0.0f) {
                m_v3.a = 1.0f;
                m_count = 1;
                m_v1.set(m_v3);
                return;
            }

            // e23
            if (d23_1 > 0.0f && d23_2 > 0.0f && d123_1 <= 0.0f) {
                float inv_d23 = 1.0f / (d23_1 + d23_2);
                m_v2.a = d23_1 * inv_d23;
                m_v3.a = d23_2 * inv_d23;
                m_count = 2;
                m_v1.set(m_v3);
                return;
            }

            // Must be in triangle123
            float inv_d123 = 1.0f / (d123_1 + d123_2 + d123_3);
            m_v1.a = d123_1 * inv_d123;
            m_v2.a = d123_2 * inv_d123;
            m_v3.a = d123_3 * inv_d123;
            m_count = 3;
        }
    }

    private Simplex simplex = new Simplex();
    private int[] saveA = new int[3];
    private int[] saveB = new int[3];
    private Vec2 closestPoint = new Vec2();
    private Vec2 d = new Vec2();
    private Vec2 temp = new Vec2();
    private Vec2 normal = new Vec2();

    /**
     * Compute the closest points between two shapes. Supports any combination of: CircleShape and
     * PolygonShape. The simplex cache is input/output. On the first call set SimplexCache.count to
     * zero.
     */
    public final void distance(DistanceOutput output, SimplexCache cache, DistanceInput input) {

        final DistanceProxy proxyA = input.proxyA;
        final DistanceProxy proxyB = input.proxyB;

        Transform transformA = input.transformA;
        Transform transformB = input.transformB;

        // Initialize the simplex.
        simplex.readCache(cache, proxyA, transformA, proxyB, transformB);

        // Get simplex vertices as an array.
        SimplexVertex[] vertices = simplex.vertices;

        // These store the vertices of the last simplex so that we
        // can check for duplicates and prevent cycling.
        // (pooled above)
        int saveCount = 0;

        simplex.getClosestPoint(closestPoint);

        // Main iteration loop
        int iter = 0;
        while (iter < MAX_ITERS) {

            // Copy simplex so we can identify duplicates.
            saveCount = simplex.m_count;
            for (int i = 0; i < saveCount; i++) {
                saveA[i] = vertices[i].indexA;
                saveB[i] = vertices[i].indexB;
            }

            switch (simplex.m_count) {
                case 1:
                    break;
                case 2:
                    simplex.solve2();
                    break;
                case 3:
                    simplex.solve3();
                    break;
                default:
                    assert false;
            }

            // If we have 3 points, then the origin is in the corresponding triangle.
            if (simplex.m_count == 3) {
                break;
            }

            // Compute closest point.
            simplex.getClosestPoint(closestPoint);

            // get search direction;
            simplex.getSearchDirection(d);

            // Ensure the search direction is numerically fit.
            if (d.lengthSquared() < JBoxSettings.EPSILON * JBoxSettings.EPSILON) {
                // The origin is probably contained by a line segment
                // or triangle. Thus the shapes are overlapped.

                // We can't return zero here even though there may be overlap.
                // In case the simplex is a point, segment, or triangle it is difficult
                // to determine if the origin is contained in the CSO or very close to it.
                break;
            }
      /*
       * SimplexVertex* vertex = vertices + simplex.m_count; vertex.indexA =
       * proxyA.GetSupport(MulT(transformA.R, -d)); vertex.wA = Mul(transformA,
       * proxyA.GetVertex(vertex.indexA)); Vec2 wBLocal; vertex.indexB =
       * proxyB.GetSupport(MulT(transformB.R, d)); vertex.wB = Mul(transformB,
       * proxyB.GetVertex(vertex.indexB)); vertex.w = vertex.wB - vertex.wA;
       */

            // Compute a tentative new simplex vertex using support points.
            SimplexVertex vertex = vertices[simplex.m_count];

            Rotation.mulTransUnsafe(transformA.q, d.negateLocal(), temp);
            vertex.indexA = proxyA.getSupport(temp);
            Transform.mulToOutUnsafe(transformA, proxyA.getVertex(vertex.indexA), vertex.wA);

            Rotation.mulTransUnsafe(transformB.q, d.negateLocal(), temp);
            vertex.indexB = proxyB.getSupport(temp);
            Transform.mulToOutUnsafe(transformB, proxyB.getVertex(vertex.indexB), vertex.wB);

            vertex.w.set(vertex.wB).subLocal(vertex.wA);

            // Iteration count is equated to the number of support point calls.
            ++iter;

            // Check for duplicate support points. This is the main termination criteria.
            boolean duplicate = false;
            for (int i = 0; i < saveCount; ++i) {
                if (vertex.indexA == saveA[i] && vertex.indexB == saveB[i]) {
                    duplicate = true;
                    break;
                }
            }

            // If we found a duplicate support point we must exit to avoid cycling.
            if (duplicate) {
                break;
            }

            // New vertex is ok and needed.
            ++simplex.m_count;
        }

        // Prepare output.
        simplex.getWitnessPoints(output.pointA, output.pointB);
        output.distance = output.pointA.distanceF(output.pointB);
        output.iterations = iter;

        // Cache the simplex.
        simplex.writeCache(cache);

        // Apply radii if requested.
        if (input.useRadii) {
            float rA = proxyA.getRadius();
            float rB = proxyB.getRadius();

            if (output.distance > rA + rB && output.distance > JBoxSettings.EPSILON) {
                // Shapes are still no overlapped.
                // Move the witness points to the outer surface.
                output.distance -= rA + rB;
                normal.set(output.pointB).subLocal(output.pointA);
                normal.getLengthAndNormalize();
                temp.set(normal).mulLocal(rA);
                output.pointA.addLocal(temp);
                temp.set(normal).mulLocal(rB);
                output.pointB.subLocal(temp);
            } else {
                // Shapes are overlapped when radii are considered.
                // Move the witness points to the middle.
                // Vec2 p = 0.5f * (output.pointA + output.pointB);
                output.pointA.addLocal(output.pointB).mulLocal(.5f);
                output.pointB.set(output.pointA);
                output.distance = 0.0f;
            }
        }
    }
}
