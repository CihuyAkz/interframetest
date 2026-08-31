package dev.fallingcloud.interframe;

import org.joml.Quaternionf;

/**
 * Immutable snapshot of the world camera for one rendered frame, taken during the level render and paired
 * up at presentation time with the captured colour image. Holds the camera's full orientation quaternion
 * (view→world, copied straight from {@code Camera#rotation()} so no Euler-order/sign assumptions can creep
 * in), its world position, the half-FOV tangents read from the live projection matrix (tanX includes
 * aspect, so sprint/zoom/shader FOV effects are all accounted for), and the projection's m22/m32 terms,
 * which the synthesiser uses to linearise depth-buffer samples for the translational (parallax) warp.
 */
public final class CameraSnapshot {
    public final boolean valid;
    /** Camera orientation, view space → world space. Never null; identity for {@link #INVALID}. */
    public final Quaternionf rot;
    public final float tanHalfFovX;
    public final float tanHalfFovY;
    public final double x, y, z;
    /** Projection matrix terms for depth linearisation: viewZ = -m32 / (m22 + ndcZ). */
    public final float projM22;
    public final float projM32;

    public static final CameraSnapshot INVALID = new CameraSnapshot();

    private CameraSnapshot() {
        this.valid = false;
        this.rot = new Quaternionf();
        this.tanHalfFovX = this.tanHalfFovY = 1;
        this.x = this.y = this.z = 0;
        this.projM22 = -1;
        this.projM32 = -0.1f;
    }

    public CameraSnapshot(Quaternionf rot, float tanHalfFovX, float tanHalfFovY,
                          double x, double y, double z, float projM22, float projM32) {
        this.valid = true;
        this.rot = rot;
        this.tanHalfFovX = tanHalfFovX;
        this.tanHalfFovY = tanHalfFovY;
        this.x = x;
        this.y = y;
        this.z = z;
        this.projM22 = projM22;
        this.projM32 = projM32;
    }

    /** Total look-rotation between the two snapshots in degrees (shortest arc, any axis). */
    public float deltaAngleDeg(CameraSnapshot other) {
        // angle(qa^-1 * qb) = 2*acos(|<qa, qb>|) — the quaternion dot product gives the half-angle cosine.
        float dot = Math.abs(rot.x * other.rot.x + rot.y * other.rot.y
                + rot.z * other.rot.z + rot.w * other.rot.w);
        return (float) Math.toDegrees(2.0 * Math.acos(Math.min(1.0f, dot)));
    }

    /** Straight-line camera movement between the two snapshots, in blocks. */
    public double distanceTo(CameraSnapshot other) {
        double dx = x - other.x, dy = y - other.y, dz = z - other.z;
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }
}
