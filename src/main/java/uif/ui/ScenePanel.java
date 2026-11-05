package uif.ui;

import uif.AstroClass;
import uif.Body;
import uif.Chamber;
import uif.Formula;
import uif.Species;
import uif.StabilityTest;
import uif.Trail;
import uif.World;

import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.GradientPaint;
import java.awt.Graphics2D;
import java.awt.RadialGradientPaint;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.MouseWheelEvent;
import java.awt.geom.AffineTransform;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Line2D;
import java.awt.geom.Path2D;
import java.awt.geom.Point2D;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;
import java.util.List;
import java.util.Map;

/** 3D-сцена: поле, замкнутая область / оверлей КУО / стороны диполя, следы, частицы, подписи и сводка. */
final class ScenePanel extends JPanel {
    private static final int TRAIL_BANDS = 24;
    private static final Color TRANSPARENT = new Color(0, 0, 0, 0);

    private final World world;
    private final Camera cam;
    private final Settings s;
    private String[] summary = {}, details = {};
    private final Rectangle detailsToggle = new Rectangle();
    private int lastX, lastY, pressX, pressY;
    private boolean looking; // левая кнопка зажата — поворот взгляда
    private static final int CLICK_PX = 5; // меньше — клик по телу / закрытие панели, больше — взгляд
    /** Alt+ЛКМ: удержание тела (Option A). */
    private Body grabbing;
    private double[] grabPlane; // точка плоскости взгляда в момент захвата
    private long grabT0;
    private final double[] grabHistT = new double[16];
    private final double[][] grabHistP = new double[16][3];
    private int grabHistN, grabHistI;
    /** Опорная скорость отпускания для m = m_e; тяжёлые — медленнее (∛m). */
    private static final double GRAB_V0 = 40;
    private List<StabilityTest.Orbit> orbits = List.of();
    private final java.util.IdentityHashMap<Body,double[]> equatorNormals = new java.util.IdentityHashMap<>();

    /** Option C: PiP inset — local Java2D cam on sticky lastFocused (bottom-right). */
    private static final int PIP_DEFAULT_W = 240, PIP_DEFAULT_H = 180;
    private static final int PIP_MIN_W = 160, PIP_MIN_H = 120;
    private static final int PIP_CHROME = 22, PIP_MARGIN = 16, PIP_GRIP = 14;
    private static final int PIP_NEIGHBORS = 48;
    /** Local cam distance vs focus radius (was 32 — moons clipped; modest pullback). */
    private static final double PIP_CAM_K = 52;
    /** Neighbor gather radius in units of pipCam.camR (was 5). */
    private static final double PIP_REACH_K = 7;
    private Body lastFocused;
    private boolean pipVisible;
    /** Session-persisted inset size (resize via NW grip). */
    private int pipW = PIP_DEFAULT_W, pipH = PIP_DEFAULT_H;
    private final Camera pipCam = new Camera();
    private final Rectangle pipBounds = new Rectangle();
    private final Rectangle pipClose = new Rectangle();
    private final Rectangle pipGrip = new Rectangle();
    /** Press started inside PiP — ignore release as scene click / look. */
    private boolean pipPress;
    private boolean pipResize;
    private int pipResizeW0, pipResizeH0;

    ScenePanel(World world, Camera cam, Settings s) {
        this.world = world;
        this.cam = cam;
        this.s = s;
        setBackground(Theme.BG);
        setOpaque(true);
        setFocusable(true);
        MouseAdapter mouse = new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) {
                requestFocusInWindow();
                lastX = pressX = e.getX();
                lastY = pressY = e.getY();
                looking = false;
                pipPress = false;
                pipResize = false;
                if (e.isPopupTrigger()) {
                    popup(e);
                    return;
                }
                if (detailsToggle.contains(e.getPoint())) {
                    s.detailsOpen = !s.detailsOpen;
                    return;
                }
                // PiP: NW grip resizes; × hides; other clicks inside do not rotate / focus main cam
                if (SwingUtilities.isLeftMouseButton(e) && pipVisible && pipBounds.contains(e.getPoint())) {
                    pipPress = true;
                    if (pipGrip.contains(e.getPoint())) {
                        pipResize = true;
                        pipResizeW0 = pipW;
                        pipResizeH0 = pipH;
                        return;
                    }
                    if (pipClose.contains(e.getPoint())) hideFocusPip();
                    return;
                }
                // Alt+ЛКМ по телу — взять (взгляд/фокус без Alt не меняются)
                if (SwingUtilities.isLeftMouseButton(e) && e.isAltDown()) {
                    Body hit = hitBody(e.getX(), e.getY());
                    if (hit != null) {
                        beginGrab(hit, e.getX(), e.getY());
                        return;
                    }
                }
                // левая — зажать и вести = поворот взгляда; клик без сдвига — ниже в released
                if (SwingUtilities.isLeftMouseButton(e)) looking = true;
            }

            @Override
            public void mouseReleased(MouseEvent e) {
                if (e.isPopupTrigger()) {
                    popup(e); // Windows открывает меню по отпусканию
                    return;
                }
                if (!SwingUtilities.isLeftMouseButton(e)) return;
                if (grabbing != null) {
                    endGrab();
                    return; // не клик-фокус
                }
                if (pipPress) {
                    pipPress = false;
                    pipResize = false;
                    looking = false;
                    updatePipCursor(e.getX(), e.getY());
                    return;
                }
                boolean wasLooking = looking;
                looking = false;
                int dx = e.getX() - pressX, dy = e.getY() - pressY;
                if (!wasLooking || dx * dx + dy * dy > CLICK_PX * CLICK_PX) return; // был поворот — не клик
                clearPick();
                Body hit = hitBody(e.getX(), e.getY());
                if (hit != null) {
                    if (onBodyClick != null) onBodyClick.accept(hit);
                    return;
                }
                if (onClick != null) onClick.run(); // клик по пустому месту закрывает панель темы
            }

            @Override
            public void mouseDragged(MouseEvent e) {
                if (pipResize && (e.getModifiersEx() & MouseEvent.BUTTON1_DOWN_MASK) != 0) {
                    // BR-anchored: NW grip — drag up/left grows (SE corner stays); session size kept
                    int maxW = Math.max(PIP_MIN_W, getWidth() / 2);
                    int maxH = Math.max(PIP_MIN_H, getHeight() / 2);
                    pipW = Math.max(PIP_MIN_W, Math.min(maxW, pipResizeW0 - (e.getX() - pressX)));
                    pipH = Math.max(PIP_MIN_H, Math.min(maxH, pipResizeH0 - (e.getY() - pressY)));
                    setCursor(java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.NW_RESIZE_CURSOR));
                    repaint();
                    return;
                }
                if (pipPress) return; // drag starting in PiP — ignore
                if (grabbing != null && (e.getModifiersEx() & MouseEvent.BUTTON1_DOWN_MASK) != 0) {
                    moveGrab(e.getX(), e.getY());
                    repaint();
                    return;
                }
                if ((e.getModifiersEx() & MouseEvent.BUTTON3_DOWN_MASK) != 0) { // правый — прицел, камера не крутится
                    pick = pick(e.getX(), e.getY());
                    repaint();
                    return;
                }
                if ((e.getModifiersEx() & MouseEvent.BUTTON1_DOWN_MASK) == 0) return;
                looking = true;
                cam.rotate(e.getX() - lastX, e.getY() - lastY);
                lastX = e.getX();
                lastY = e.getY();
            }

            @Override
            public void mouseMoved(MouseEvent e) {
                updatePipCursor(e.getX(), e.getY());
            }

            @Override
            public void mouseWheelMoved(MouseWheelEvent e) {
                if (grabbing != null) return; // во время удержания колесо не зумит
                cam.zoom(e.getPreciseWheelRotation());
            }
        };
        addMouseListener(mouse);
        addMouseMotionListener(mouse);
        addMouseWheelListener(mouse);
    }

    private void updatePipCursor(int x, int y) {
        if (pipVisible && pipGrip.contains(x, y)) {
            setCursor(java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.NW_RESIZE_CURSOR));
        } else {
            setCursor(java.awt.Cursor.getDefaultCursor());
        }
    }

    private void beginGrab(Body b, int sx, int sy) {
        grabbing = b;
        grabPlane = b.p.clone();
        world.setHeld(b);
        grabT0 = System.nanoTime();
        grabHistN = 0;
        grabHistI = 0;
        moveGrab(sx, sy);
    }

    private void moveGrab(int sx, int sy) {
        if (grabbing == null || !world.parts.contains(grabbing)) {
            endGrab();
            return;
        }
        double[] q = intersectViewPlane(sx, sy, grabPlane);
        grabbing.p[0] = q[0];
        grabbing.p[1] = q[1];
        grabbing.p[2] = q[2];
        grabbing.v[0] = grabbing.v[1] = grabbing.v[2] = 0;
        recordGrabSample(q);
    }

    private void recordGrabSample(double[] p) {
        double t = (System.nanoTime() - grabT0) * 1e-9;
        grabHistT[grabHistI] = t;
        grabHistP[grabHistI][0] = p[0];
        grabHistP[grabHistI][1] = p[1];
        grabHistP[grabHistI][2] = p[2];
        grabHistI = (grabHistI + 1) % grabHistT.length;
        if (grabHistN < grabHistT.length) grabHistN++;
    }

    private void endGrab() {
        Body b = grabbing;
        grabbing = null;
        if (b != null && world.parts.contains(b)) {
            double[] v = releaseVelocity(b);
            b.v[0] = v[0];
            b.v[1] = v[1];
            b.v[2] = v[2];
        }
        world.setHeld(null);
        grabPlane = null;
        grabHistN = 0;
    }

    /** Скорость отпускания: Δp/Δt за ~80 мс, усечённая по ∛m. */
    private double[] releaseVelocity(Body b) {
        double[] v = new double[3];
        if (grabHistN < 2) return v;
        double tNow = grabHistT[(grabHistI - 1 + grabHistT.length) % grabHistT.length];
        double[] pNow = grabHistP[(grabHistI - 1 + grabHistT.length) % grabHistT.length];
        double tOld = tNow, bestDt = 0;
        double[] pOld = pNow;
        for (int k = 1; k < grabHistN; k++) {
            int i = (grabHistI - 1 - k + grabHistT.length * 2) % grabHistT.length;
            double dt = tNow - grabHistT[i];
            if (dt >= 0.04 && dt <= 0.12) {
                tOld = grabHistT[i];
                pOld = grabHistP[i];
                bestDt = dt;
                break;
            }
            if (dt > bestDt && dt < 0.2) {
                bestDt = dt;
                tOld = grabHistT[i];
                pOld = grabHistP[i];
            }
        }
        if (bestDt < 1e-4) return v;
        for (int k = 0; k < 3; k++) v[k] = (pNow[k] - pOld[k]) / bestDt;
        double speed = Math.sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2]);
        double vmax = GRAB_V0 / Math.cbrt(Math.max(1, b.m / Formula.M_E));
        if (speed > vmax && speed > 0) {
            double s = vmax / speed;
            v[0] *= s;
            v[1] *= s;
            v[2] *= s;
        }
        return v;
    }

    /**
     * Луч курсора ∩ плоскость взгляда через {@code planePoint} (нормаль = forward камеры).
     */
    double[] intersectViewPlane(int sx, int sy, double[] planePoint) {
        double[] eye = cam.eye(), fwd = cam.forward();
        double[] onRay = pick(sx, sy);
        double dx = onRay[0] - eye[0], dy = onRay[1] - eye[1], dz = onRay[2] - eye[2];
        double denom = fwd[0] * dx + fwd[1] * dy + fwd[2] * dz;
        if (Math.abs(denom) < 1e-12) return planePoint.clone();
        double t = (fwd[0] * (planePoint[0] - eye[0]) + fwd[1] * (planePoint[1] - eye[1]) + fwd[2] * (planePoint[2] - eye[2])) / denom;
        return new double[]{eye[0] + t * dx, eye[1] + t * dy, eye[2] + t * dz};
    }

    /** Если тело исчезло из сцены — отпустить. Вызывать из кадра App. */
    void tickGrab() {
        if (grabbing != null && !world.parts.contains(grabbing)) endGrab();
    }

    /** Контекстное меню (разделы справочника); задаётся App после сборки окна. */
    private java.util.function.Supplier<javax.swing.JPopupMenu> contextMenu;

    private Runnable onClick;
    private java.util.function.Consumer<Body> onBodyClick;

    void setOnClick(Runnable r) {
        onClick = r;
    }

    /** Левый клик по телу — плавно навести камеру на его центр (задаёт App). */
    void setOnBodyClick(java.util.function.Consumer<Body> r) {
        onBodyClick = r;
    }

    /** Sticky lastFocused + show PiP inset (Option C). Empty LMB does not clear this. */
    void showFocusPip(Body b) {
        if (b == null) return;
        lastFocused = b;
        pipVisible = true;
    }

    void hideFocusPip() {
        pipVisible = false;
        // keep lastFocused so a later show can reopen; App clears on body death / reset
    }

    boolean isFocusPipVisible() {
        return pipVisible;
    }

    Body lastFocused() {
        return lastFocused;
    }

    /** Clear sticky focus + hide PiP (reset / body gone). */
    void clearFocusPip() {
        lastFocused = null;
        pipVisible = false;
    }

    void setOrbits(List<StabilityTest.Orbit> o) {
        orbits = o == null ? List.of() : o;
    }

    /** Ближайшее к курсору тело в экранных координатах (с запасом по радиусу); null — мимо. */
    Body hitBody(int sx, int sy) {
        Body best = null;
        double bestZ = Double.POSITIVE_INFINITY;
        for (Body b : world.parts) {
            double[] sc = cam.project(b.p);
            if (sc == null) continue;
            double rr = Math.max(10, b.rad * sc[3] + 6);
            double dx = sc[0] - sx, dy = sc[1] - sy;
            if (dx * dx + dy * dy <= rr * rr && sc[2] < bestZ) {
                best = b;
                bestZ = sc[2];
            }
        }
        return best;
    }

    void setContextMenu(java.util.function.Supplier<javax.swing.JPopupMenu> menu) {
        contextMenu = menu;
    }

    private void popup(MouseEvent e) {
        pick = pick(e.getX(), e.getY());
        popupHit = hitBody(e.getX(), e.getY());
        repaint();
        if (contextMenu != null) contextMenu.get().show(this, e.getX(), e.getY());
    }

    /** Тело под курсором в момент ПКМ (для «стабилизировать»); null — мимо. */
    Body popupHit() {
        return popupHit;
    }

    /** Точка, выбранная правым кликом (для меню «создать здесь»); null — не выбрана. */
    double[] pickPoint() {
        return pick;
    }

    /** Убрать метку выбора (после создания частицы). */
    void clearPick() {
        if (pick != null) {
            pick = null;
            repaint();
        }
    }

    /**
     * Луч из курсора: пересечение с полом (y = floorY); если пол почти параллелен лучу — плоскость
     * через цель камеры. Так видно, где именно в пространстве встанет частица.
     */
    double[] pick(int sx, int sy) {
        double kx = (sx - cam.width / 2.0) / cam.focal, ky = -(sy - cam.height / 2.0) / cam.focal;
        double sp = Math.sin(cam.pitch), cp = Math.cos(cam.pitch);
        double zc = cam.camR;
        double den = ky * cp + sp;
        if (den > 1e-4) {
            double zf = (floorY - cam.ty + cam.camR * sp) / den;
            if (zf > Camera.NEAR && zf < cam.camR * 30) zc = zf;
        }
        double x1 = kx * zc, y2 = ky * zc, z2 = zc - cam.camR;
        double py = y2 * cp + z2 * sp, z1 = -y2 * sp + z2 * cp;
        double cy = Math.cos(cam.yaw), sy2 = Math.sin(cam.yaw);
        return new double[]{x1 * cy + z1 * sy2 + cam.tx, py + cam.ty, -x1 * sy2 + z1 * cy + cam.tz};
    }

    /** Метка «сюда встанет частица»: кольцо, перекрестие и линия до пола. */
    private void drawPick(Graphics2D g) {
        double[] sc = cam.project(pick);
        if (sc == null) return;
        int x = (int) Math.round(sc[0]), y = (int) Math.round(sc[1]);
        g.setStroke(new BasicStroke(1.4f));
        g.setColor(Theme.rgba(0x6DA7EC, 0.95));
        g.draw(new Ellipse2D.Double(x - 6, y - 6, 12, 12));
        g.draw(new Line2D.Double(x - 11, y, x - 3, y));
        g.draw(new Line2D.Double(x + 3, y, x + 11, y));
        g.draw(new Line2D.Double(x, y - 11, x, y - 3));
        g.draw(new Line2D.Double(x, y + 3, x, y + 11));
        double[] fl = cam.project(pick[0], floorY, pick[2]);
        if (fl != null) {
            g.setColor(Theme.rgba(0x6DA7EC, 0.45));
            g.setStroke(new BasicStroke(1f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER, 10f, new float[]{2, 4}, 0));
            g.draw(new Line2D.Double(x, y, fl[0], fl[1]));
        }
    }

    /** Метка выбора точки (null — нет). */
    private double[] pick;
    /** Тело под ПКМ (null — пустое место). */
    private Body popupHit;

    void setOverlay(String[] summary, String[] details) {
        this.summary = summary;
        this.details = details;
    }

    @Override
    protected void paintComponent(Graphics g0) {
        Graphics2D g = (Graphics2D) g0.create();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
        cam.width = getWidth();
        cam.height = getHeight();
        g.setColor(Theme.BG);
        g.fillRect(0, 0, getWidth(), getHeight());
        sceneSize();
        if (s.bodyField) updateHeat();
        updateDeformations();
        equatorNormals.clear();
        if (s.showGround) drawGround(g);
        if (s.showField) drawField(g);
        if (world.chamber.on) drawChamber(g);
        if (s.showKuo) drawKuo(g);
        if (s.shaders && GlScene.get() != null) { // шейдеры: следы и тела одним слоем поверх линий пар
            drawProtonLine(g);
            drawDipoles(g);
            if (!drawGl(g)) {
                if (s.showTrail) drawTrails(g);
                drawBodies2D(g);
            } else if (s.bodyField) {
                drawClouds(g); // облака — тем же мягким слоем, что и без шейдеров: картинка одна и та же
            }
        } else {
            if (s.showTrail) drawTrails(g);
            drawProtonLine(g);
            drawDipoles(g);
            drawBodies2D(g);
        }
        if (s.showEquator) drawEquators(g, cam, world.parts, getWidth(), getHeight());
        if (s.showOrbits && !orbits.isEmpty()) drawOrbits(g);
        if (s.showForces && world.parts.size() <= FORCES_MAX) drawForces(g);
        if (s.showPolesSides && world.parts.size() <= FORCES_MAX) drawPolesSides(g);
        if (world.tidalEquator) drawTidalEquator(g);
        if (world.stabilize != null) drawStabilize(g);
        if (s.showLabels && world.parts.size() <= LABELS_MAX) drawLabels(g);
        if (s.showGround) drawAxes(g);
        if (pick != null) drawPick(g);
        drawOverlay(g);
        if (pipVisible && lastFocused != null) drawFocusPip(g);
        g.dispose();
    }

    /** Bottom-right PiP: local cam locked on lastFocused + nearby bodies; orbits if showOrbits. */
    private void drawFocusPip(Graphics2D g0) {
        if (!world.parts.contains(lastFocused)) {
            clearFocusPip();
            return;
        }
        int W = getWidth(), H = getHeight();
        int maxW = Math.max(PIP_MIN_W, W / 2), maxH = Math.max(PIP_MIN_H, H / 2);
        if (pipW > maxW) pipW = maxW;
        if (pipH > maxH) pipH = maxH;
        if (W < pipW + 2 * PIP_MARGIN || H < pipH + 2 * PIP_MARGIN) return;
        int x0 = W - PIP_MARGIN - pipW;
        int y0 = H - PIP_MARGIN - pipH;
        pipBounds.setBounds(x0, y0, pipW, pipH);
        pipClose.setBounds(x0 + pipW - 22, y0, 22, PIP_CHROME);
        pipGrip.setBounds(x0, y0, PIP_GRIP, PIP_GRIP); // NW — не путать с × справа

        Body focus = lastFocused;
        int bodyH = pipH - PIP_CHROME;
        pipCam.width = pipW;
        pipCam.height = bodyH;
        pipCam.yaw = cam.yaw;
        pipCam.pitch = cam.pitch;
        pipCam.tx = focus.p[0];
        pipCam.ty = focus.p[1];
        pipCam.tz = focus.p[2];
        pipCam.ox = pipCam.oy = pipCam.oz = 0;
        pipCam.focal = 620;
        // Floor camR, then open up a bit if moons / lighter satellites need frame space
        double floorR = Math.max(0.35, focus.rad * PIP_CAM_K);
        double halfPx = 0.40 * Math.min(pipW, bodyH);
        double moonSearch = focus.rad * 40; // Io…Ganymede-scale; Earth–Moon still partial
        double farthest = 0;
        for (Body b : world.parts) {
            if (b == focus) continue;
            boolean sat = b.astro == AstroClass.MOON || b.m < focus.m * 0.95;
            if (!sat) continue;
            double d = World.dist(b.p, focus.p);
            if (d > 0 && d <= moonSearch) farthest = Math.max(farthest, d);
        }
        double fitR = farthest > 0 ? farthest * pipCam.focal / halfPx : floorR;
        // Modest: ≤2.2× floor; keep focus disk ≳7 px
        double capR = Math.min(floorR * 2.2, focus.rad * pipCam.focal / 7.0);
        pipCam.camR = Math.max(Camera.MIN_R * 2, Math.min(Camera.MAX_R, Math.max(floorR, Math.min(fitR, capR))));

        Graphics2D g = (Graphics2D) g0.create();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);

        // frame + chrome
        g.setColor(Theme.PANEL);
        g.fillRoundRect(x0, y0, pipW, pipH, 6, 6);
        g.setColor(Theme.ACCENT);
        g.setStroke(new BasicStroke(2f));
        g.drawRoundRect(x0, y0, pipW - 1, pipH - 1, 6, 6);
        g.setColor(Theme.BG);
        g.fillRect(x0 + 1, y0 + PIP_CHROME, pipW - 2, bodyH - 1);

        g.setFont(Theme.UI_SMALL);
        g.setColor(Theme.ACCENT);
        String title = "Фокус · " + pipLabel(focus);
        FontMetrics fm = g.getFontMetrics();
        int titleX = x0 + PIP_GRIP + 4; // leave NW grip clear
        int maxTitle = pipW - (titleX - x0) - 28;
        if (maxTitle > 8 && fm.stringWidth(title) > maxTitle) {
            while (title.length() > 4 && fm.stringWidth(title + "…") > maxTitle) {
                title = title.substring(0, title.length() - 1);
            }
            title = title + "…";
        }
        g.drawString(title, titleX, y0 + 15);
        g.setColor(Theme.MUTED);
        g.drawString("×", x0 + pipW - 16, y0 + 15);

        // NW resize grip (short diagonals in top-left chrome)
        g.setStroke(new BasicStroke(1.2f));
        g.setColor(Theme.rgba(0x898781, 0.85));
        int gx = x0 + 4, gy = y0 + 4;
        for (int i = 0; i < 3; i++) {
            int o = 3 + i * 3;
            g.drawLine(gx, gy + o, gx + o, gy);
        }

        // body viewport
        Graphics2D vg = (Graphics2D) g.create(x0 + 1, y0 + PIP_CHROME, pipW - 2, bodyH - 1);
        vg.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        double reach = Math.max(pipCam.camR * PIP_REACH_K, moonSearch);
        double reach2 = reach * reach;
        List<Body> near = new java.util.ArrayList<>();
        List<double[]> dist = new java.util.ArrayList<>();
        for (Body b : world.parts) {
            double dx = b.p[0] - focus.p[0], dy = b.p[1] - focus.p[1], dz = b.p[2] - focus.p[2];
            double d2 = dx * dx + dy * dy + dz * dz;
            if (b != focus && d2 > reach2) continue;
            near.add(b);
            dist.add(new double[]{d2});
        }
        if (near.size() > PIP_NEIGHBORS) {
            Integer[] ord = new Integer[near.size()];
            for (int i = 0; i < ord.length; i++) ord[i] = i;
            java.util.Arrays.sort(ord, (a, b) -> Double.compare(dist.get(a)[0], dist.get(b)[0]));
            List<Body> keep = new java.util.ArrayList<>(PIP_NEIGHBORS);
            for (int i = 0; i < PIP_NEIGHBORS; i++) keep.add(near.get(ord[i]));
            if (!keep.contains(focus)) {
                keep.set(PIP_NEIGHBORS - 1, focus);
            }
            near = keep;
        }

        if (s.showOrbits && !orbits.isEmpty()) {
            drawPipOrbits(vg, focus, reach * 1.5);
        }

        List<double[]> proj = new java.util.ArrayList<>(near.size());
        List<Body> drawn = new java.util.ArrayList<>(near.size());
        for (Body b : near) {
            double[] sc = pipCam.project(b.p);
            if (sc == null) continue;
            drawn.add(b);
            proj.add(sc);
        }
        Integer[] order = new Integer[drawn.size()];
        for (int i = 0; i < order.length; i++) order[i] = i;
        java.util.Arrays.sort(order, (a, b) -> Double.compare(proj.get(b)[2], proj.get(a)[2]));
        for (int i : order) {
            Body b = drawn.get(i);
            double[] sc = proj.get(i);
            double f = pipFog(sc[2]);
            double[] dg = diskGlow(b, sc[3], f);
            double rr = dg[0], gr = dg[1];
            int col = bodyColor(b);
            double alpha = bodyAlpha(b);
            double pad = Math.max(dg[0], dg[1]) * (s.deform == Settings.DeformMode.OFF ? 1 : DEFORM_PAD);
            if (sc[0] + pad < 0 || sc[0] - pad > pipW || sc[1] + pad < 0 || sc[1] - pad > bodyH) continue;
            int bi = world.parts.indexOf(b);
            double[] df = deformOf(b, bi, pipCam);
            paintBodyDisk(vg, sc[0], sc[1], rr, gr, col, alpha * f, mix(bodyCore(b), BG_RGB, 1 - f), df);
        }
        if (s.showEquator) drawEquators(vg, pipCam, near, pipW, bodyH);
        vg.dispose();
        g.dispose();
    }

    private void drawEquators(Graphics2D g, Camera view, List<Body> bodies, int width, int height) {
        List<EquatorOverlay.Disk> disks = new java.util.ArrayList<>();
        for (Body body : bodies) {
            double[] center = view.project(body.p);
            if (center == null) continue;
            double fade = view == pipCam ? pipFog(center[2]) : fog(center[2]);
            double radius = diskGlow(body, center[3], fade)[0];
            double reach = radius * DEFORM_PAD;
            if (center[0]+reach<0 || center[0]-reach>width || center[1]+reach<0 || center[1]-reach>height) continue;
            double[] normal = null;
            if (radius >= 4) {
                normal = equatorNormals.get(body);
                if (normal == null) {
                    VisualDeformation state = deformations.get(body);
                    if (s.deform != Settings.DeformMode.OFF && state != null) normal = state.axis();
                    if (normal == null) normal = world.equatorOf(body, equatorNormals);
                    if (normal == null) normal = new double[]{0,1,0};
                    equatorNormals.put(body, normal);
                }
            }
            disks.add(new EquatorOverlay.Disk(center,radius,deformOf(body,0,view),normal,0.8*bodyAlpha(body)*fade));
        }
        EquatorOverlay.paint(g, view, disks, width, height);
    }

    private void drawPipOrbits(Graphics2D g, Body focus, double maxDist) {
        g.setStroke(new BasicStroke(1.2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND, 10f, new float[]{4, 4}, 0));
        final int N = 48;
        double max2 = maxDist * maxDist;
        for (StabilityTest.Orbit o : orbits) {
            double dx = o.c()[0] - focus.p[0], dy = o.c()[1] - focus.p[1], dz = o.c()[2] - focus.p[2];
            if (dx * dx + dy * dy + dz * dz > max2) continue;
            Path2D.Double path = new Path2D.Double();
            boolean pen = false;
            for (int i = 0; i <= N; i++) {
                double a = 2 * Math.PI * i / N;
                double ca = Math.cos(a), sa = Math.sin(a);
                double x = o.c()[0] + o.a() * ca * o.u()[0] + o.b() * sa * o.v()[0];
                double y = o.c()[1] + o.a() * ca * o.u()[1] + o.b() * sa * o.v()[1];
                double z = o.c()[2] + o.a() * ca * o.u()[2] + o.b() * sa * o.v()[2];
                double[] sc = pipCam.project(x, y, z);
                if (sc == null) {
                    pen = false;
                    continue;
                }
                if (!pen) {
                    path.moveTo(sc[0], sc[1]);
                    pen = true;
                } else path.lineTo(sc[0], sc[1]);
            }
            g.setColor(Theme.rgba(0x898781, 0.25)); // same muted orbit style as main scene
            g.draw(path);
        }
    }

    private double pipFog(double zc) {
        double half = pipCam.camR * 2;
        double u = (zc - (pipCam.camR - half)) / (2 * half);
        if (u < 0) u = 0;
        if (u > 1) u = 1;
        return 1 - 0.55 * u;
    }

    private static String pipLabel(Body b) {
        return switch (b.astro) {
            case STAR -> "звезда";
            case PLANET -> "планета";
            case MOON -> "луна";
            case ASTEROID -> "астероид";
            case NONE -> b.kind.name;
        };
    }

    /** Связанные орбиты: пунктирный эллипс в плоскости движения вокруг централа. */
    private void drawOrbits(Graphics2D g) {
        g.setStroke(new BasicStroke(1.4f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND, 10f, new float[]{5, 5}, 0));
        final int N = 64;
        for (StabilityTest.Orbit o : orbits) {
            Path2D.Double path = new Path2D.Double();
            boolean pen = false;
            for (int i = 0; i <= N; i++) {
                double a = 2 * Math.PI * i / N;
                double ca = Math.cos(a), sa = Math.sin(a);
                double x = o.c()[0] + o.a() * ca * o.u()[0] + o.b() * sa * o.v()[0];
                double y = o.c()[1] + o.a() * ca * o.u()[1] + o.b() * sa * o.v()[1];
                double z = o.c()[2] + o.a() * ca * o.u()[2] + o.b() * sa * o.v()[2];
                double[] sc = cam.project(x, y, z);
                if (sc == null) {
                    pen = false;
                    continue;
                }
                if (!pen) {
                    path.moveTo(sc[0], sc[1]);
                    pen = true;
                } else path.lineTo(sc[0], sc[1]);
            }
            g.setColor(Theme.rgba(0x898781, 0.25)); // muted gray, low alpha — don't compete with bodies
            g.draw(path);
        }
    }

    /**
     * Мягкий слой в 1/LOW разрешения экрана: пятна накладываются «поверх» в премультиплицированных
     * цветах, затем слой растягивается на экран с билинейной фильтрацией. Свечение размыто, мелких
     * деталей в нём нет — а пикселей в LOW² раз меньше, чем у RadialGradientPaint на весь экран.
     */
    private static final int LOW = 4;

    private static final class SoftLayer {
        private BufferedImage img;
        private int[] px;
        private float[] r, g, b, a;
        private int w, h;

        void begin(int screenW, int screenH) {
            int nw = Math.max(1, (screenW + LOW - 1) / LOW), nh = Math.max(1, (screenH + LOW - 1) / LOW);
            if (img == null || nw != w || nh != h) {
                w = nw;
                h = nh;
                img = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB_PRE);
                px = ((DataBufferInt) img.getRaster().getDataBuffer()).getData();
                r = new float[w * h];
                g = new float[w * h];
                b = new float[w * h];
                a = new float[w * h];
            } else {
                java.util.Arrays.fill(r, 0);
                java.util.Arrays.fill(g, 0);
                java.util.Arrays.fill(b, 0);
                java.util.Arrays.fill(a, 0);
            }
        }

        /** Пятно в экранных координатах радиуса rad; альфа = scale·lut[доля радиуса]. */
        void splat(double cx, double cy, double rad, int rgb, float[] lut, double scale) {
            double x = cx / LOW, y = cy / LOW, rl = rad / LOW;
            if (!(rl > 0.3)) return;
            int x0 = Math.max(0, (int) (x - rl)), x1 = Math.min(w - 1, (int) (x + rl) + 1);
            int y0 = Math.max(0, (int) (y - rl)), y1 = Math.min(h - 1, (int) (y + rl) + 1);
            float cr = ((rgb >> 16) & 255) / 255f, cg = ((rgb >> 8) & 255) / 255f, cb = (rgb & 255) / 255f;
            int last = lut.length - 1;
            for (int j = y0; j <= y1; j++) {
                double dy = j + 0.5 - y;
                for (int i = x0; i <= x1; i++) {
                    double dx = i + 0.5 - x, d = Math.sqrt(dx * dx + dy * dy) / rl;
                    if (d >= 1) continue;
                    double fi = d * last;
                    int i0 = (int) fi;
                    double lv = i0 < last ? lut[i0] + (lut[i0 + 1] - lut[i0]) * (fi - i0) : lut[last];
                    float al = (float) (scale * lv);
                    if (al <= 0) continue;
                    int k = j * w + i;
                    float keep = 1 - al;
                    r[k] = r[k] * keep + cr * al;
                    g[k] = g[k] * keep + cg * al;
                    b[k] = b[k] * keep + cb * al;
                    a[k] = a[k] * keep + al;
                }
            }
        }

        /**
         * Сложение полей: в каждый пиксель добавляется интенсивность v = scale·lut[доля радиуса]
         * (без обрезки), цвет копится с весом v. После всех тел — resolveSum(): альфа = cap·(1 − e^(−Σv)),
         * цвет — средний по вкладам. Так поле двух соседних тел — одно общее облако, а не два наложенных.
         */
        void add(double cx, double cy, double rad, int rgb, float[] lut, double scale) {
            double x = cx / LOW, y = cy / LOW, rl = rad / LOW;
            if (!(rl > 0.3)) return;
            int x0 = Math.max(0, (int) (x - rl)), x1 = Math.min(w - 1, (int) (x + rl) + 1);
            int y0 = Math.max(0, (int) (y - rl)), y1 = Math.min(h - 1, (int) (y + rl) + 1);
            float cr = ((rgb >> 16) & 255) / 255f, cg = ((rgb >> 8) & 255) / 255f, cb = (rgb & 255) / 255f;
            int last = lut.length - 1;
            for (int j = y0; j <= y1; j++) {
                double dy = j + 0.5 - y;
                for (int i = x0; i <= x1; i++) {
                    double dx = i + 0.5 - x, d = Math.sqrt(dx * dx + dy * dy) / rl;
                    if (d >= 1) continue;
                    double fi = d * last;
                    int i0 = (int) fi;
                    double lv = i0 < last ? lut[i0] + (lut[i0 + 1] - lut[i0]) * (fi - i0) : lut[last];
                    float v = (float) (scale * lv);
                    if (v <= 0) continue;
                    int k = j * w + i;
                    r[k] += cr * v;
                    g[k] += cg * v;
                    b[k] += cb * v;
                    a[k] += v;
                }
            }
        }

        void resolveSum(float cap) {
            for (int k = 0; k < a.length; k++) {
                float v = a[k];
                if (v <= 0) continue;
                float al = (float) (cap * -Math.expm1(-v)); // мягкое насыщение: структура видна и в сумме
                r[k] = r[k] / v * al;
                g[k] = g[k] / v * al;
                b[k] = b[k] / v * al;
                a[k] = al;
            }
        }

        void draw(Graphics2D gr) {
            for (int k = 0; k < px.length; k++) {
                px[k] = (int) (a[k] * 255) << 24 | (int) (r[k] * 255) << 16 | (int) (g[k] * 255) << 8 | (int) (b[k] * 255);
            }
            Object hint = gr.getRenderingHint(RenderingHints.KEY_INTERPOLATION);
            gr.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            gr.drawImage(img, 0, 0, w * LOW, h * LOW, null);
            if (hint != null) gr.setRenderingHint(RenderingHints.KEY_INTERPOLATION, hint);
        }
    }

    private final SoftLayer fieldLayer = new SoftLayer(), wallLayer = new SoftLayer(), cloudLayer = new SoftLayer(),
            kuoLayer = new SoftLayer();
    /** Пятно стенки: линейный спад от центра к краю (как прежний двухточечный градиент). */
    private static final float[] RAMP = new float[33];

    static {
        for (int k = 0; k < RAMP.length; k++) RAMP[k] = 1 - k / (RAMP.length - 1f);
    }

    /** Свечение = Σ Q_j·N_j всех тел (общее поле), отнесённое к полю протона на r0: та же QN, что и в динамике. */
    private void drawField(Graphics2D g) {
        double w0 = world.QN(world.r0, 0, Formula.M_P);
        double cut = 0.02;
        fieldLayer.begin(getWidth(), getHeight());
        float[] lut = new float[65];
        for (Body b : world.parts) {
            if (b.sign < 0) continue; // свечение — притягивающее поле; поле d− отталкивающее, его не рисуем
            double[] sc = cam.project(b.p);
            if (sc == null) continue;
            double worldR = world.r0 * Math.sqrt(b.N / Formula.M_P / cut);
            double screenR = worldR * sc[3];
            if (!(screenR > 4)) continue;
            for (int k = 0; k < lut.length; k++) {
                double u = k / (lut.length - 1.0), taper = (1 - u * u) * (1 - u * u); // к краю облака — плавно в ноль
                lut[k] = (float) (taper * world.QN(u * worldR, b.rad, b.N) / w0);
            }
            // поля складываются до обрезки яркости: пара тел светится одним общим облаком
            fieldLayer.add(sc[0], sc[1], screenR, b.atom == 0 ? 0x5AAAFF : 0xFFA05A, lut, 1);
        }
        fieldLayer.resolveSum(0.35f);
        fieldLayer.draw(g);
    }

    // ---------- частица полем ----------

    /** Скорость с прошлого кадра и «нагрев» каждого тела: {vx, vy, vz, h}. */
    private java.util.IdentityHashMap<Body, double[]> heat = new java.util.IdentityHashMap<>();
    private double heatTime = Double.NaN;

    /**
     * Частица полем — две накладки: тусклый широкий ореол (поле тела Q·N) и маленькая яркая точка
     * (ядро частицы). Ореол тянется до {@link #CLOUD_CUT} поля протона на r0, гаснет как (1 − u)^2.4 и
     * берёт {@link #HALO_ALPHA} яркости тела. Точка — радиус {@link #POINT_K} радиусов тела, яркость
     * {@link #POINT_ALPHA}, цвет — цвет тела, смешанный с белым на {@link #POINT_WHITE}: центр ярче
     * границы, «типа яркая точка».
     */
    private static final double CLOUD_CUT = 0.05, HALO_ALPHA = 0.25;
    private static final double POINT_K = 2.5, POINT_ALPHA = 0.85, POINT_WHITE = 0.15;
    /**
     * Наибольшая непрозрачность: яркость складывается и мягко насыщается по пикселю, как у слоя
     * «поле» ({@code resolveSum}), поэтому сотни тел дают дымку, а не белый кадр.
     */
    private static final float CLOUD_CAP = 0.95f;

    /**
     * «Нагрев» — сглаженное ускорение тела, как в indexGPU.html: h ← 0.92·h + 0.08·цель, цель —
     * |Δv/Δt| за кадр (модельное время) по шкале 0…2: 1 — ускорение электрона на r0 от протона,
     * каждые 10³ раз — ±1. На паузе не меняется. Только для рисования.
     */
    void updateHeat() {
        double dt = world.time - heatTime;
        double aRef = Formula.F_ALPHA * Formula.K_V * world.QN(world.r0, 0, Formula.M_P) / Formula.M_E;
        java.util.IdentityHashMap<Body, double[]> next = new java.util.IdentityHashMap<>(world.parts.size() * 2);
        for (Body b : world.parts) {
            double[] st = heat.get(b);
            if (st == null) st = new double[]{b.v[0], b.v[1], b.v[2], 0};
            else if (dt > 0) {
                double ax = b.v[0] - st[0], ay = b.v[1] - st[1], az = b.v[2] - st[2];
                double x = Math.sqrt(ax * ax + ay * ay + az * az) / dt / aRef;
                double target = x > 0 ? Math.max(0, Math.min(2, 1 + Math.log10(x) / 3)) : 0;
                st[3] = 0.92 * st[3] + 0.08 * target;
                st[0] = b.v[0];
                st[1] = b.v[1];
                st[2] = b.v[2];
            }
            next.put(b, st);
        }
        heat = next;
        heatTime = world.time;
    }

    private double heatOf(Body b) {
        double[] st = heat.get(b);
        return st == null ? 0 : st[3];
    }

    /**
     * Облако тела: {ядро rc и край R в мире, цвет, яркость}. Ядро rc — радиус, на котором поле тела
     * равно полю протона на r0 (r0·√(N/M_p)); край R — где оно падает до {@link #CLOUD_CUT}. Яркость —
     * поле на поверхности, отнесённое к полю протона на r0; всё ×(1 + 0.45·h). Цвет — по виду частицы:
     * протон, нейтрон и электрон отличаются (те же цвета, что в легенде и в режиме «шар»), потому что
     * массы протона и нейтрона отличаются на 0,16 % и шкала по массе их не разделяет.
     */
    /**
     * Цвет облака/шара: астро-класс (STAR/PLANET/MOON/ASTEROID) перекрывает CUSTOM;
     * частицы и ручной CUSTOM ({@link AstroClass#NONE}) — цвет вида (CloudTest).
     */
    int bodyColor(Body b) {
        return b.astro != AstroClass.NONE ? b.astro.color : b.kind.color;
    }

    private static int bodyCore(Body b) {
        return b.astro != AstroClass.NONE ? b.astro.core : b.kind.core;
    }

    private static double bodyGlow(Body b) {
        return b.astro != AstroClass.NONE ? b.astro.glow : b.kind.glow;
    }

    private static double bodyAlpha(Body b) {
        return b.astro == AstroClass.ASTEROID ? 0.55 : b.kind.alpha;
    }

    /**
     * Экранный диск = true rad·s (почти без пола). Ореол — max(диск·glow, {@link AstroClass#haloPx})
     * только у STAR/PLANET/MOON; астероиды и NONE без mass-halo.
     */
    private static double[] diskGlow(Body b, double scale, double fog) {
        double disk = Math.max(0.12, b.rad * scale);
        double gr = Math.max(disk * bodyGlow(b), disk);
        double halo = AstroClass.haloPx(b);
        if (halo > 0) gr = Math.max(gr, halo);
        return new double[]{disk, gr, fog};
    }

    /**
     * Option A oblate «апельсин»: {cos, sin, flat, bulge} — своё на каждое тело.
     * flat&lt;1 вдоль оси силы/прилива, bulge&gt;1 по экватору.
     */
    private static final double[] DEFORM_ID = VisualDeformation.IDENTITY;
    private static final double DEFORM_PAD = VisualDeformation.MAX_ASPECT;
    private static final int TIDAL_HUBS = 32;
    private Body[] tidalHubs;
    /** Δv/Δt каждый кадр (fallback, когда host a не fresh — resident GPU). */
    private java.util.IdentityHashMap<Body, double[]> deformVel = new java.util.IdentityHashMap<>();
    private double deformVelTime = Double.NaN;
    private java.util.IdentityHashMap<Body, VisualDeformation> deformations = new java.util.IdentityHashMap<>();
    private Settings.DeformMode previousDeformMode = Settings.DeformMode.OFF;
    private long previousDeformPaint;

    private void updateDeformations() {
        long now = System.nanoTime();
        double dt = previousDeformPaint == 0 ? 1.0 / 60 : Math.max(0, (now - previousDeformPaint) / 1e9);
        previousDeformPaint = now;
        if (s.deform != previousDeformMode) {
            deformations.clear(); deformVel.clear(); deformVelTime = Double.NaN;
            previousDeformMode = s.deform;
        }
        if (s.deform == Settings.DeformMode.OFF) return;
        updateDeformVel();
        if (s.deform == Settings.DeformMode.TIDAL) ensureTidalHubs();
        java.util.IdentityHashMap<Body, VisualDeformation> next = new java.util.IdentityHashMap<>();
        for (int i = 0; i < world.parts.size(); i++) {
            Body body = world.parts.get(i);
            VisualDeformation state = deformations.get(body);
            if (state == null) state = new VisualDeformation();
            double[] target = deformationTarget(body, i);
            state.update(target[0], target[1], target[2], target[3], dt);
            next.put(body, state);
        }
        deformations = next;
    }

    private double[] deformOf(Body b, int index, Camera view) {
        if (s.deform == Settings.DeformMode.OFF) return DEFORM_ID;
        VisualDeformation state = deformations.get(b);
        return state == null ? DEFORM_ID : state.project(view);
    }

    private double[] deformationTarget(Body b, int index) {
        double ax = 0, ay = 0, az = 0, strength = 0;
        if (s.deform == Settings.DeformMode.ACCEL) {
            if (world.accelFresh()) {
                double[][] la = world.lastAccel();
                if (la != null && index >= 0 && index < la.length) {
                    ax = la[index][0];
                    ay = la[index][1];
                    az = la[index][2];
                }
            } else {
                double[] st = deformVel.get(b);
                if (st != null) {
                    ax = st[3];
                    ay = st[4];
                    az = st[5];
                }
            }
            double mag = Math.sqrt(ax * ax + ay * ay + az * az);
            double aRef = Formula.F_ALPHA * Formula.K_V * world.QN(world.r0, 0, Formula.M_P) / Formula.M_E;
            // декады |a|/aRef → 0…1 (звезда≈0, планета мягко, луна сильнее; без раннего clamp всех в max)
            if (aRef > 0 && mag > 0) {
                double decades = Math.log10(mag / aRef);
                strength = Math.max(0, Math.min(1, (decades + 1) / 3));
            }
        } else { // TIDAL — ось и сила свои на тело (хаб = max |N|/r³ у этого тела)
            double bestTide = 0, bestAng = 0;
            double[] p = b.p;
            for (Body h : tidalHubs) {
                if (h == null || h == b) continue;
                double dx = h.p[0] - p[0], dy = h.p[1] - p[1], dz = h.p[2] - p[2];
                double r2 = dx * dx + dy * dy + dz * dz;
                if (!(r2 > 0)) continue;
                double r = Math.sqrt(r2);
                double tide = Math.abs(h.N) / (r2 * r + 1e-30);
                if (tide > bestTide) {
                    bestTide = tide;
                    ax = dx;
                    ay = dy;
                    az = dz;
                    bestAng = h.rad / r;
                }
            }
            strength = VisualDeformation.tidalStrength(bestAng);
        }
        return new double[]{ax, ay, az, strength};
    }

    /** Топ массивных хабов — каждый paint. */
    private void ensureTidalHubs() {
        List<Body> parts = world.parts;
        int n = parts.size(), k = Math.min(TIDAL_HUBS, n);
        tidalHubs = new Body[k];
        double[] score = new double[k];
        int filled = 0;
        for (Body b : parts) {
            double sc = Math.abs(b.N);
            if (filled < k) {
                tidalHubs[filled] = b;
                score[filled] = sc;
                filled++;
                continue;
            }
            int weak = 0;
            for (int i = 1; i < k; i++) if (score[i] < score[weak]) weak = i;
            if (sc > score[weak]) {
                tidalHubs[weak] = b;
                score[weak] = sc;
            }
        }
        if (filled < k) {
            Body[] trim = new Body[filled];
            System.arraycopy(tidalHubs, 0, trim, 0, filled);
            tidalHubs = trim;
        }
    }

    /** Каждый кадр при ACCEL: копить a≈Δv/Δt (нужно после resident GPU; не пропускать при fresh). */
    private void updateDeformVel() {
        if (s.deform != Settings.DeformMode.ACCEL) return;
        double dt = world.time - deformVelTime;
        java.util.IdentityHashMap<Body, double[]> next = new java.util.IdentityHashMap<>(world.parts.size() * 2);
        for (Body b : world.parts) {
            double[] st = deformVel.get(b);
            if (st == null) st = new double[]{b.v[0], b.v[1], b.v[2], 0, 0, 0};
            else if (dt > 0) {
                st[3] = (b.v[0] - st[0]) / dt;
                st[4] = (b.v[1] - st[1]) / dt;
                st[5] = (b.v[2] - st[2]) / dt;
                st[0] = b.v[0];
                st[1] = b.v[1];
                st[2] = b.v[2];
            }
            next.put(b, st);
        }
        deformVel = next;
        deformVelTime = world.time;
    }

    /** Залить диск/эллипс (Option A deform); paint уже задан. */
    private static void fillDisk(Graphics2D g, double x, double y, double r, double[] d) {
        if (d == DEFORM_ID || (d[2] == 1 && d[3] == 1)) {
            g.fill(circle(x, y, r));
            return;
        }
        AffineTransform old = g.getTransform();
        g.translate(x, y);
        g.transform(new AffineTransform(d[0], d[1], -d[1], d[0], 0, 0));
        g.scale(d[2], d[3]);
        g.fill(circle(0, 0, r));
        g.setTransform(old);
    }
    private double[] cloudOf(Body b) {
        double h = heatOf(b);
        double mm = Math.max(0, Math.min(1, Math.log(b.m / Formula.M_E) / Math.log(Formula.M_P / Formula.M_E)));
        int c = bodyColor(b);
        double a = Math.max(0.10, Math.min(0.85, 0.14 + 0.22 * h + 0.15 * mm));
        double r1 = world.r0 * Math.sqrt(Math.abs(b.N) / Formula.M_P), rc = Math.max(r1, b.rad);
        double grow = 1 + 0.45 * h;
        return new double[]{rc * grow, r1 / Math.sqrt(CLOUD_CUT) * grow, c, a * (r1 / rc) * (r1 / rc)};
    }

    /** Тела без шейдеров: шарами или облаками поля. */
    private void drawBodies2D(Graphics2D g) {
        if (s.bodyField) drawClouds(g);
        else drawBodies(g);
    }

    /** Частица полем: ореол Q·N (тусклый и широкий) и яркая точка-ядро, вклады насыщаются до {@link #CLOUD_CAP}. */
    private void drawClouds(Graphics2D g) {
        cloudLayer.begin(getWidth(), getHeight());
        float[] lut = new float[129];
        for (Body b : world.parts) {
            double[] sc = cam.project(b.p);
            if (sc == null) continue;
            double[] cl = cloudOf(b);
            double R = Math.max(2, cl[1] * sc[3]);
            double fade = fog(sc[2]);
            // ореол — поле тела, тусклое и широкое; к краю гаснет как (1 − u)^2.4
            for (int i = 0; i < lut.length; i++) {
                lut[i] = (float) Math.pow(1 - i / (lut.length - 1.0), 2.4);
            }
            cloudLayer.add(sc[0], sc[1], R, (int) cl[2], lut, cl[3] * HALO_ALPHA * fade / CLOUD_CAP);
            // точка — ядро частицы: маленькое, почти белое и яркое
            double core = Math.max(3, POINT_K * b.rad * sc[3]);
            for (int i = 0; i < lut.length; i++) {
                double u = i / (lut.length - 1.0);
                lut[i] = (float) ((1 - u) * (1 - u));
            }
            int white = mix((int) cl[2], 0xFFFFFF, POINT_WHITE);
            cloudLayer.add(sc[0], sc[1], core, white, lut, POINT_ALPHA * fade / CLOUD_CAP);
        }
        cloudLayer.resolveSum(CLOUD_CAP);
        cloudLayer.draw(g);
    }

    /** Направления для отрисовки стенки: силуэт и подсветка прогиба. */
    private static final double[][] FINE = fibonacci(1000);
    private static final int BINS = 240;

    private static double[][] fibonacci(int m) {
        double[][] d = new double[m][];
        for (int k = 0; k < m; k++) {
            double z = 1 - (2 * k + 1.0) / m, r = Math.sqrt(1 - z * z), ph = k * Math.PI * (3 - Math.sqrt(5));
            d[k] = new double[]{r * Math.cos(ph), r * Math.sin(ph), z};
        }
        return d;
    }

    /** Свет для стеклянной оболочки: сверху-слева-спереди в системе камеры (x вправо, y вверх, z от зрителя). */
    private static final double[] LIGHT = norm3(-0.5, 0.7, -0.6);

    private static double[] norm3(double x, double y, double z) {
        double l = Math.sqrt(x * x + y * y + z * z);
        return new double[]{x / l, y / l, z / l};
    }

    /**
     * Замкнутая область — стеклянная оболочка настоящей формы R(n̂): точки поверхности затеняются по нормали
     * (диффуз, блик, светлый ободок на краю), задняя половина тусклее и под передней; вмятины
     * видны по рельефу, при включённом «поле» — ещё и цветом (наружу — тёплый, внутрь — холодный,
     * полный цвет при |R − R̄| = 5% R₀). Контур — опорная функция точек; пунктир — R₀.
     */
    private void drawChamber(Graphics2D g) {
        Chamber ch = world.chamber;
        double[] sc = cam.project(ch.c);
        if (sc == null) return;
        double rr = ch.R() * sc[3], r0 = ch.restRadius() * sc[3]; // пунктир — радиус покоя при текущих давлениях
        if (!(rr > 1) || !(r0 > 0)) return; // вырожденная сфера — нечего рисовать
        double Rm = ch.R(), full = 0.05 * ch.R0;
        boolean show = s.showField; // цвет прогиба — вместе с полем частиц (кнопка «поле», G)
        int m = FINE.length;
        double[] px = new double[m], py = new double[m], pd = new double[m], pz = new double[m];
        int[] col = new int[m];
        double[] alpha = new double[m];
        boolean[] front = new boolean[m];
        int cnt = 0;
        for (double[] n : FINE) {
            double[] sp = ch.surfacePoint(n);
            double r = sp[0];
            double[] p = cam.project(ch.c[0] + n[0] * r, ch.c[1] + n[1] * r, ch.c[2] + n[2] * r);
            if (p == null) continue;
            // нормаль в системе камеры; взгляд из камеры на точку — вдоль +z (к зрителю: −z)
            double[] N = cam.turn(sp[1], sp[2], sp[3]);
            double nv = -N[2]; // > 0 — нормаль к зрителю, передняя половина
            double diff = Math.max(0, N[0] * LIGHT[0] + N[1] * LIGHT[1] + N[2] * LIGHT[2]);
            // отражённый свет R = 2(N·L)N − L; блик = (R·V)^24, V = (0,0,−1)
            double nl = N[0] * LIGHT[0] + N[1] * LIGHT[1] + N[2] * LIGHT[2];
            double rv = -(2 * nl * N[2] - LIGHT[2]);
            double spec = Math.pow(Math.max(0, rv), 24);
            double rim = Math.pow(1 - Math.abs(nv), 2);
            int base = 0x9678FF;
            if (show) {
                double t = Math.min(1, Math.abs(r - Rm) / full);
                base = mix(base, r > Rm ? 0xFF9A5A : 0x5AC8FF, 0.6 * t);
            }
            int c = mix(mix(0x000000, base, 0.25 + 0.6 * diff), 0xFFFFFF, Math.min(1, 0.5 * spec + 0.35 * rim));
            double f = fog(p[2]);
            px[cnt] = p[0] - sc[0];
            py[cnt] = p[1] - sc[1];
            pd[cnt] = r - Rm;
            pz[cnt] = p[3];
            col[cnt] = c;
            alpha[cnt] = 0.10 * (0.5 + rim) * (nv > 0 ? 1.6 : 1) * f;
            front[cnt++] = nv > 0;
        }
        if (cnt < 3) return;
        double[][] edge = new double[BINS][];
        double[] dev = new double[BINS];
        Path2D.Double outline = new Path2D.Double();
        for (int b = 0; b < BINS; b++) {
            double a = 2 * Math.PI * b / BINS, ux = Math.cos(a), uy = Math.sin(a), h = -1;
            for (int i = 0; i < cnt; i++) {
                double pr = px[i] * ux + py[i] * uy;
                if (pr > h) {
                    h = pr;
                    dev[b] = pd[i];
                }
            }
            edge[b] = new double[]{sc[0] + h * ux, sc[1] + h * uy};
            if (b == 0) outline.moveTo(edge[b][0], edge[b][1]);
            else outline.lineTo(edge[b][0], edge[b][1]);
        }
        outline.closePath();
        // оболочка: пятна размером с шаг между точками (≈ 2R·√(π/m) в мире), сперва задняя
        // половина, поверх — передняя; слой обрезан по силуэту
        double blobWorld = 2.2 * Rm * Math.sqrt(Math.PI / m);
        wallLayer.begin(getWidth(), getHeight());
        for (int pass = 0; pass < 2; pass++) {
            for (int i = 0; i < cnt; i++) {
                if (front[i] != (pass == 1)) continue;
                // вплотную пятна огромны — ограничиваем: иначе 1000 пятен на весь экран, кадр ~0.2 с
                wallLayer.splat(sc[0] + px[i], sc[1] + py[i], Math.min(blobWorld * pz[i], 0.15 * (getWidth() + getHeight())), col[i], RAMP, alpha[i]);
            }
        }
        java.awt.Shape clip = g.getClip();
        g.clip(outline);
        wallLayer.draw(g);
        g.setClip(clip);
        g.setStroke(new BasicStroke(1f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        for (int a = 0; a < BINS; a++) {
            int b = (a + 1) % BINS;
            double d = 0.5 * (dev[a] + dev[b]), t = show ? Math.min(1, Math.abs(d) / full) : 0;
            g.setColor(Theme.rgba(mix(0xAA8CFF, d > 0 ? 0xFF9A5A : 0x5AC8FF, t), 0.35 + 0.4 * t));
            g.draw(new Line2D.Double(edge[a][0], edge[a][1], edge[b][0], edge[b][1]));
        }
        g.setColor(Theme.rgba(0xAA8CFF, 0.25));
        g.setStroke(new BasicStroke(1f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER, 10f, new float[]{3, 6}, 0));
        g.draw(circle(sc[0], sc[1], r0));
    }

    /**
     * Оверлей КУО (кнопка «отображение КУО» в «Виде») — зеркало стеклянной оболочки камеры, но без физики:
     * дальняя сфера вокруг центра масс с визуальной деформацией R(n̂) от дефицита массы / q
     * (вмятина к скоплениям, лёгкий выступ с противоположной стороны; в замкнутом — ещё сжатие с q),
     * пятна/конусы телесного угла и HUD q. Силы и {@link uif.Kuo} не трогает.
     */
    private static final int KUO_DEFICIT_MAX = 48;
    /** Амплитуда вмятины к массам и ответного выступа (доля R₀); усиливается при росте q. */
    private static final double KUO_DENT = 0.22, KUO_BULGE = 0.07, KUO_Q_SHRINK = 0.20;

    private void drawKuo(Graphics2D g) {
        List<Body> parts = world.parts;
        if (parts.isEmpty()) {
            drawKuoHud(g);
            return;
        }
        double[] c = world.centerOfMass();
        double[] sc = cam.project(c);
        if (sc == null) {
            drawKuoHud(g);
            return;
        }
        double R0 = Math.max(sceneL * 1.75, 0.8);
        for (Body b : parts) R0 = Math.max(R0, World.dist(b.p, c) + Math.max(b.rad * 3, 0.05));
        double rr0 = R0 * sc[3];
        if (!(rr0 > 2)) {
            drawKuoHud(g);
            return;
        }
        double q = world.kuo.q();
        double qVis = world.kuo.closed ? q : 0; // бесконечный — без глобального сжатия от запаса
        double shell = world.kuo.closed ? Math.max(0.12, 1 - q) : 0.55;

        Body[] focus = kuoDeficitBodies(parts);
        double[][] dir = new double[focus.length][3];
        double[] half = new double[focus.length];
        double[] w = new double[focus.length];
        double sumM = 0;
        for (int i = 0; i < focus.length; i++) {
            Body b = focus[i];
            double dx = b.p[0] - c[0], dy = b.p[1] - c[1], dz = b.p[2] - c[2];
            double d = Math.sqrt(dx * dx + dy * dy + dz * dz);
            double m = Math.abs(b.m);
            sumM += m;
            w[i] = m;
            if (d < 1e-12) {
                dir[i][0] = 1;
                half[i] = Math.PI;
                continue;
            }
            dir[i][0] = dx / d;
            dir[i][1] = dy / d;
            dir[i][2] = dz / d;
            half[i] = Math.min(0.55, Math.atan2(Math.max(b.rad * 2.2, 0.02), d) * 1.35);
        }
        if (sumM > 0) for (int i = 0; i < w.length; i++) w[i] /= sumM;

        int m = FINE.length;
        double[] px = new double[m], py = new double[m], pd = new double[m], pz = new double[m];
        int[] col = new int[m];
        double[] alpha = new double[m];
        boolean[] front = new boolean[m];
        double Rm = 0;
        int cnt = 0;
        double full = 0.05 * R0; // как у камеры: полный цвет при |ΔR| ≈ 5% R₀
        for (double[] n : FINE) {
            double r = kuoShellR(n, R0, qVis, dir, w, half);
            Rm += r;
            double[] p = cam.project(c[0] + n[0] * r, c[1] + n[1] * r, c[2] + n[2] * r);
            if (p == null) continue;
            // нормаль ≈ радиус для мягкой деформации; освещение как у камеры
            double[] N = cam.turn(n[0], n[1], n[2]);
            double nv = -N[2];
            double diff = Math.max(0, N[0] * LIGHT[0] + N[1] * LIGHT[1] + N[2] * LIGHT[2]);
            double nl = N[0] * LIGHT[0] + N[1] * LIGHT[1] + N[2] * LIGHT[2];
            double rv = -(2 * nl * N[2] - LIGHT[2]);
            double spec = Math.pow(Math.max(0, rv), 24);
            double rim = Math.pow(1 - Math.abs(nv), 2);
            double hole = 0;
            for (int i = 0; i < focus.length; i++) {
                double cos = n[0] * dir[i][0] + n[1] * dir[i][1] + n[2] * dir[i][2];
                double ang = Math.acos(Math.max(-1, Math.min(1, cos)));
                if (ang < half[i]) hole = Math.max(hole, 1 - ang / half[i]);
            }
            // цвет прогиба: вмятина (дефицит) — холоднее, выступ — теплее; плюс затемнение «дыры»
            double tDev = Math.min(1, Math.abs(r - R0 * (1 - KUO_Q_SHRINK * qVis)) / full);
            int base = mix(KUO_RGB, r < R0 * (1 - KUO_Q_SHRINK * qVis) ? 0x3A90C8 : 0xFF9A5A, 0.55 * tDev);
            base = mix(base, 0x0A1A28, 0.45 * hole);
            int cRgb = mix(mix(0x000000, base, 0.22 + 0.55 * diff * (1 - 0.55 * hole)), 0xFFFFFF,
                    Math.min(1, 0.45 * spec + 0.3 * rim) * (1 - 0.65 * hole));
            double f = fog(p[2]);
            px[cnt] = p[0] - sc[0];
            py[cnt] = p[1] - sc[1];
            pd[cnt] = r;
            pz[cnt] = p[3];
            col[cnt] = cRgb;
            alpha[cnt] = 0.09 * shell * (0.45 + rim) * (nv > 0 ? 1.55 : 1) * f * (1 - 0.7 * hole);
            front[cnt++] = nv > 0;
        }
        if (cnt < 3) {
            drawKuoHud(g);
            return;
        }
        Rm /= Math.max(1, FINE.length);
        for (int i = 0; i < cnt; i++) pd[i] -= Rm;
        double[][] edge = new double[BINS][];
        double[] dev = new double[BINS];
        Path2D.Double outline = new Path2D.Double();
        for (int b = 0; b < BINS; b++) {
            double a = 2 * Math.PI * b / BINS, ux = Math.cos(a), uy = Math.sin(a), h = -1;
            for (int i = 0; i < cnt; i++) {
                double pr = px[i] * ux + py[i] * uy;
                if (pr > h) {
                    h = pr;
                    dev[b] = pd[i];
                }
            }
            edge[b] = new double[]{sc[0] + h * ux, sc[1] + h * uy};
            if (b == 0) outline.moveTo(edge[b][0], edge[b][1]);
            else outline.lineTo(edge[b][0], edge[b][1]);
        }
        outline.closePath();
        double blobWorld = 2.2 * Rm * Math.sqrt(Math.PI / m);
        kuoLayer.begin(getWidth(), getHeight());
        for (int pass = 0; pass < 2; pass++) {
            for (int i = 0; i < cnt; i++) {
                if (front[i] != (pass == 1)) continue;
                kuoLayer.splat(sc[0] + px[i], sc[1] + py[i],
                        Math.min(blobWorld * pz[i], 0.15 * (getWidth() + getHeight())), col[i], RAMP, alpha[i]);
            }
        }
        java.awt.Shape clip = g.getClip();
        g.clip(outline);
        kuoLayer.draw(g);
        g.setClip(clip);
        g.setStroke(new BasicStroke(1.2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        for (int a = 0; a < BINS; a++) {
            int b = (a + 1) % BINS;
            double d = 0.5 * (dev[a] + dev[b]), t = Math.min(1, Math.abs(d) / full);
            g.setColor(Theme.rgba(mix(KUO_RGB, d > 0 ? 0xFF9A5A : 0x3A90C8, t), (0.28 + 0.45 * t) * shell));
            g.draw(new Line2D.Double(edge[a][0], edge[a][1], edge[b][0], edge[b][1]));
        }
        // пунктир — недеформированная R₀ (как R₀ у камеры)
        g.setColor(Theme.rgba(KUO_RGB, 0.22 * shell));
        g.setStroke(new BasicStroke(1f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER, 10f, new float[]{3, 6}, 0));
        g.draw(circle(sc[0], sc[1], rr0));

        // конусы дефицита — к уже прогнутой поверхности
        for (int i = 0; i < focus.length; i++) {
            Body b = focus[i];
            double[] sb = cam.project(b.p);
            if (sb == null) continue;
            double ang = half[i];
            double[] side = ortho(dir[i]);
            double[] a1 = rotToward(dir[i], side, ang);
            double[] a2 = rotToward(dir[i], side, -ang);
            double r1 = kuoShellR(a1, R0, qVis, dir, w, half);
            double r2 = kuoShellR(a2, R0, qVis, dir, w, half);
            double[] p1 = cam.project(c[0] + a1[0] * r1, c[1] + a1[1] * r1, c[2] + a1[2] * r1);
            double[] p2 = cam.project(c[0] + a2[0] * r2, c[1] + a2[1] * r2, c[2] + a2[2] * r2);
            if (p1 == null || p2 == null) continue;
            Path2D.Double cone = new Path2D.Double();
            cone.moveTo(sb[0], sb[1]);
            cone.lineTo(p1[0], p1[1]);
            cone.lineTo(p2[0], p2[1]);
            cone.closePath();
            g.setColor(Theme.rgba(0x0A1520, 0.22 * shell));
            g.fill(cone);
            g.setColor(Theme.rgba(KUO_RGB, 0.28));
            g.setStroke(new BasicStroke(1f));
            g.draw(cone);
            g.setColor(Theme.rgba(KUO_RGB, 0.35));
            g.setStroke(new BasicStroke(2.2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            g.draw(new Line2D.Double(p1[0], p1[1], p2[0], p2[1]));
        }
        drawKuoHud(g);
    }

    /**
     * Визуальный радиус оболочки КУО в направлении n̂: вмятина к массам (дефицит),
     * лёгкий выступ с противоположной стороны, глобальное сжатие от q (замкнутый).
     */
    private static double kuoShellR(double[] n, double R0, double q,
                                   double[][] dir, double[] w, double[] half) {
        double dent = 0, anti = 0;
        for (int i = 0; i < w.length; i++) {
            double cos = n[0] * dir[i][0] + n[1] * dir[i][1] + n[2] * dir[i][2];
            double widen = 0.5 + 0.5 * Math.min(1, half[i] / 0.35);
            dent += w[i] * Math.pow(Math.max(0, cos), 2.4) * widen;
            anti += w[i] * Math.pow(Math.max(0, -cos), 2.0);
        }
        double amp = KUO_DENT * (1 + 0.65 * q);
        double bulge = KUO_BULGE * (1 + 0.35 * q);
        double scale = 1 - KUO_Q_SHRINK * q;
        double r = R0 * scale * (1 - amp * dent + bulge * anti);
        return Math.max(0.4 * R0 * scale, r);
    }

    /** До {@link #KUO_DEFICIT_MAX} самых массивных тел — конусы/пятна дефицита. */
    private static Body[] kuoDeficitBodies(List<Body> parts) {
        int n = parts.size();
        if (n <= KUO_DEFICIT_MAX) return parts.toArray(Body[]::new);
        Body[] best = new Body[KUO_DEFICIT_MAX];
        double[] mass = new double[KUO_DEFICIT_MAX];
        int filled = 0;
        for (Body b : parts) {
            double m = Math.abs(b.m);
            if (filled < KUO_DEFICIT_MAX) {
                best[filled] = b;
                mass[filled++] = m;
                continue;
            }
            int weak = 0;
            for (int i = 1; i < KUO_DEFICIT_MAX; i++) if (mass[i] < mass[weak]) weak = i;
            if (m > mass[weak]) {
                best[weak] = b;
                mass[weak] = m;
            }
        }
        return best;
    }

    private static double[] ortho(double[] n) {
        double ax = Math.abs(n[0]), ay = Math.abs(n[1]), az = Math.abs(n[2]);
        double[] a = ax < ay && ax < az ? new double[]{0, -n[2], n[1]}
                : ay < az ? new double[]{-n[2], 0, n[0]} : new double[]{-n[1], n[0], 0};
        double l = Math.sqrt(a[0] * a[0] + a[1] * a[1] + a[2] * a[2]);
        return new double[]{a[0] / l, a[1] / l, a[2] / l};
    }

    /** Поворот единичного n к оси t на угол ang (малый телесный угол). */
    private static double[] rotToward(double[] n, double[] t, double ang) {
        double ca = Math.cos(ang), sa = Math.sin(ang);
        double[] r = {n[0] * ca + t[0] * sa, n[1] * ca + t[1] * sa, n[2] * ca + t[2] * sa};
        double l = Math.sqrt(r[0] * r[0] + r[1] * r[1] + r[2] * r[2]);
        return new double[]{r[0] / l, r[1] / l, r[2] / l};
    }

    /** Полоса q / 1−q / netGain — companion к сфере (Visual 1); физику не читает заново. */
    private void drawKuoHud(Graphics2D g) {
        int w = 228, h = 64, x = 14, y = getHeight() - h - 16;
        if (y < 8) return;
        g.setColor(Theme.rgba(0x121820, 0.82));
        g.fillRoundRect(x, y, w, h, 8, 8);
        g.setColor(Theme.rgba(KUO_RGB, 0.45));
        g.setStroke(new BasicStroke(1f));
        g.drawRoundRect(x, y, w, h, 8, 8);
        g.setFont(Theme.UI_SMALL);
        double q = world.kuo.q(), ng = world.kuo.netGain();
        String title = world.kuo.closed
                ? "КУО замкнут · q = " + Theme.fixed(q, 3)
                : "КУО бесконечный · q = 0";
        g.setColor(Theme.TEXT);
        g.drawString(title, x + 10, y + 18);
        int barX = x + 10, barY = y + 28, barW = w - 20, barH = 9;
        g.setColor(Theme.rgba(0xFFFFFF, 0.12));
        g.fillRoundRect(barX, barY, barW, barH, 3, 3);
        int fill = (int) Math.round(barW * Math.max(0, Math.min(1, q)));
        if (fill > 0) {
            g.setColor(Theme.rgba(KUO_RGB, 0.9));
            g.fillRoundRect(barX, barY, fill, barH, 3, 3);
        }
        g.setColor(Theme.MUTED);
        String foot = world.kuo.closed
                ? "1 − q = " + Theme.fixed(1 - q, 3) + " · итог "
                + (ng < 0 ? "−" : "") + Theme.beta(Math.abs(ng)) + "·W"
                : "дефицит = масса соседа (монография)";
        g.drawString(foot, x + 10, y + h - 10);
    }

    /**
     * Следы всех частиц, у каждого тела свой цвет (Body.trailColor). Яркость 0.35·(1 − возраст/TRAIL_LEN); рисуются
     * полосами по TRAIL_BANDS — одна обводка на полосу вместо одной на отрезок.
     */
    private void drawTrails(Graphics2D g0) {
        // большая сцена: тонкие несглаженные линии — сглаженная обводка 200 следов занимала полкадра
        boolean simple = world.parts.size() > LARGE;
        Graphics2D g = (Graphics2D) g0.create();
        if (simple) g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_OFF);
        else g.setStroke(new BasicStroke(1.5f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        int len = Formula.TRAIL_LEN;
        for (Body b : world.parts) {
            Trail tr = b.trail;
            int n = tr.size();
            if (n < 2) continue;
            double[][] pts = new double[n][];
            for (int i = 0; i < n; i++) pts[i] = cam.project(tr.x(i), tr.y(i), tr.z(i));
            for (int band = 0; band < TRAIL_BANDS; band++) {
                double ageHi = len * (1 - (double) band / TRAIL_BANDS), ageLo = len * (1 - (band + 1.0) / TRAIL_BANDS);
                int iLo = Math.max(1, (int) Math.ceil(n - 1 - ageHi)), iHi = Math.min(n - 1, (int) Math.floor(n - 1 - ageLo));
                if (iLo > iHi) continue;
                Path2D.Double path = new Path2D.Double();
                boolean pen = false;
                for (int i = iLo; i <= iHi; i++) {
                    double[] A = pts[i - 1], B = pts[i];
                    if (A == null || B == null) {
                        pen = false;
                        continue;
                    }
                    if (!pen) {
                        path.moveTo(A[0], A[1]);
                        pen = true;
                    }
                    path.lineTo(B[0], B[1]);
                }
                double[] mid = pts[(iLo + iHi) / 2];
                double f = mid != null ? fog(mid[2]) : 1;
                g.setColor(Theme.rgba(b.trailColor, 0.35 * f * (band + 0.5) / TRAIL_BANDS)); // ярче прежних 0.10 — иначе цвета не различить
                g.draw(path);
            }
        }
        g.dispose();
    }

    /** Пунктир между первыми двумя протонами (по центрам масс нуклонов). */
    private void drawProtonLine(Graphics2D g) {
        List<World.Nucleon> ps = world.nucleons().stream().filter(n -> n.kind() == Species.P).toList();
        if (ps.size() < 2) return;
        double[] a = cam.project(World.lump(ps.get(0).bodies()).c()), b = cam.project(World.lump(ps.get(1).bodies()).c());
        if (a == null || b == null) return;
        g.setColor(new Color(160, 190, 220, 64));
        g.setStroke(new BasicStroke(1f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER, 10f, new float[]{4, 5}, 0));
        g.draw(new Line2D.Double(a[0], a[1], b[0], b[1]));
    }

    // ---------- подсказки глубины ----------

    /** Размер сцены L (для пола и дымки) и высота пола; пересчитываются раз в кадр. */
    private double sceneL = 2, floorY;

    private void sceneSize() {
        double L;
        if (world.chamber.on) L = 1.15 * world.chamber.R();
        else {
            double[] c = world.centerOfMass();
            L = 1.2;
            for (Body b : world.parts) L = Math.max(L, World.dist(b.p, c) * 1.25);
        }
        sceneL = Math.min(L, 30);
        floorY = cam.ty - sceneL;
    }

    /** Дымка: 1 — на ближней стороне сцены (глубина цели − L) и ближе, 0.45 — на дальней (цель + L). */
    private double fog(double zc) {
        double u = (zc - (cam.camR - sceneL)) / (2 * sceneL);
        return Math.max(0.45, Math.min(1, 1 - 0.55 * u));
    }

    private static final int BG_RGB = Theme.BG.getRGB() & 0xFFFFFF;

    /** Шаг сетки: 1, 2 или 5 ·10ⁿ, около L/3. */
    private static double niceStep(double v) {
        double p = Math.pow(10, Math.floor(Math.log10(v))), m = v / p;
        return (m < 1.5 ? 1 : m < 3.5 ? 2 : 5) * p;
    }

    /**
     * Пол — сетка в плоскости y = цель − L, привязанная к мировым координатам (при движении
     * камеры линии не «едут»); тускнеет к краям и вдаль. Под каждой частицей — её проекция на
     * пол и пунктир до неё: видно и высоту, и глубину.
     */
    private void drawGround(Graphics2D g) {
        double step = niceStep(sceneL / 2), half = 2.5 * sceneL;
        double x0 = Math.floor((cam.tx - half) / step) * step, z0 = Math.floor((cam.tz - half) / step) * step;
        int lines = (int) Math.ceil(2 * half / step) + 1, seg = 12;
        g.setStroke(new BasicStroke(1f));
        for (int axis = 0; axis < 2; axis++) {
            for (int l = 0; l <= lines; l++) {
                double fixed = (axis == 0 ? x0 : z0) + l * step;
                for (int k = 0; k < seg; k++) {
                    double u0 = -half + 2 * half * k / seg, u1 = -half + 2 * half * (k + 1) / seg;
                    double[] A = axis == 0 ? cam.project(fixed, floorY, cam.tz + u0) : cam.project(cam.tx + u0, floorY, fixed);
                    double[] B = axis == 0 ? cam.project(fixed, floorY, cam.tz + u1) : cam.project(cam.tx + u1, floorY, fixed);
                    if (A == null || B == null) continue;
                    double off = axis == 0 ? fixed - cam.tx : fixed - cam.tz, um = 0.5 * (u0 + u1);
                    double edge = 1 - Math.min(1, Math.sqrt(off * off + um * um) / half);
                    double al = 0.5 * edge * fog(0.5 * (A[2] + B[2]));
                    if (al < 0.005) continue;
                    g.setColor(Theme.rgba(0xC3C2B7, al)); // нейтральная сетка пола, как в макете
                    g.draw(new Line2D.Double(A[0], A[1], B[0], B[1]));
                }
            }
        }
        BasicStroke dash = new BasicStroke(1f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER, 10f, new float[]{2, 4}, 0);
        double flat = Math.max(0.15, Math.abs(Math.sin(cam.pitch)));
        if (world.parts.size() > LARGE) return; // тени и пунктиры тысяч тел — только шум
        for (Body b : world.parts) {
            if (b.p[1] <= floorY) continue;
            double[] sc = cam.project(b.p), sh = cam.project(b.p[0], floorY, b.p[2]);
            if (sc == null || sh == null) continue;
            double f = fog(sh[2]), r = Math.max(2, 1.4 * b.rad * sh[3]);
            int col = bodyColor(b);
            g.setColor(Theme.rgba(col, 0.22 * f));
            g.fill(new Ellipse2D.Double(sh[0] - r, sh[1] - r * flat, 2 * r, 2 * r * flat));
            g.setColor(Theme.rgba(col, 0.18 * f));
            g.setStroke(dash);
            g.draw(new Line2D.Double(sc[0], sc[1], sh[0], sh[1]));
        }
    }

    /** Оси X/Y/Z в левом нижнем углу — поворачиваются вместе с камерой; дальняя ось тусклее. */
    private void drawAxes(Graphics2D g) {
        double cx = 46, cy = getHeight() - 46, len = 28;
        Object[][] axes = {{new double[]{1, 0, 0}, 0xFF6A6A, "X"}, {new double[]{0, 1, 0}, 0x6AD66A, "Y"}, {new double[]{0, 0, 1}, 0x5A9AFF, "Z"}};
        double[][] t = new double[3][];
        Integer[] order = {0, 1, 2};
        for (int i = 0; i < 3; i++) {
            double[] d = (double[]) axes[i][0];
            t[i] = cam.turn(d[0], d[1], d[2]);
        }
        java.util.Arrays.sort(order, (a, b) -> Double.compare(t[b][2], t[a][2]));
        g.setFont(Theme.FONT);
        g.setStroke(new BasicStroke(2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        for (int i : order) {
            double ex = cx + t[i][0] * len, ey = cy - t[i][1] * len, al = 0.55 + 0.4 * (-t[i][2] * 0.5 + 0.5);
            g.setColor(Theme.rgba((int) axes[i][1], al));
            g.draw(new Line2D.Double(cx, cy, ex, ey));
            g.drawString((String) axes[i][2], (float) (cx + t[i][0] * (len + 9) - 3), (float) (cy - t[i][1] * (len + 9) + 4));
        }
    }

    /**
     * Частицы от дальних к ближним: экранный радиус = rad·s — в масштабе объёма; свечение и
     * объёмное ядро (блик сверху-слева, тень снизу-справа); вдали — тусклее (дымка).
     */
    private void drawBodies(Graphics2D g) {
        List<Body> bodies = new java.util.ArrayList<>();
        List<double[]> proj = new java.util.ArrayList<>();
        List<Integer> idx = new java.util.ArrayList<>();
        List<Body> parts = world.parts;
        for (int pi = 0; pi < parts.size(); pi++) {
            Body b = parts.get(pi);
            double[] sc = cam.project(b.p);
            if (sc == null) continue;
            bodies.add(b);
            proj.add(sc);
            idx.add(pi);
        }
        Integer[] order = new Integer[bodies.size()];
        for (int i = 0; i < order.length; i++) order[i] = i;
        java.util.Arrays.sort(order, (a, b) -> Double.compare(proj.get(b)[2], proj.get(a)[2]));
        boolean simple = bodies.size() > LARGE;
        if (simple) {
            drawDots(g, bodies, proj, order, idx);
            return;
        }
        for (int i : order) {
            Body b = bodies.get(i);
            double[] sc = proj.get(i);
            double f = fog(sc[2]);
            double[] dg = diskGlow(b, sc[3], f);
            double rr = dg[0], gr = dg[1];
            double pad = Math.max(rr, gr) * (s.deform == Settings.DeformMode.OFF ? 1 : DEFORM_PAD);
            // вне экрана — не рисовать (вплотную шары огромные и часто целиком за краем)
            if (sc[0] + pad < 0 || sc[0] - pad > getWidth() || sc[1] + pad < 0 || sc[1] - pad > getHeight()) continue;
            double[] df = deformOf(b, idx.get(i), cam);
            paintBodyDisk(g, sc[0], sc[1], rr, gr, bodyColor(b), bodyAlpha(b) * f, mix(bodyCore(b), BG_RGB, 1 - f), df);
        }
    }

    /** Свечение + ядро; при deform — ориентированный эллипс (тот же look, что GlScene). */
    private static void paintBodyDisk(Graphics2D g, double x, double y, double rr, double gr,
                                      int col, double alpha, int core, double[] d) {
        boolean def = d != DEFORM_ID && !(d[2] == 1 && d[3] == 1);
        if (!def) {
            g.setPaint(new RadialGradientPaint(new Point2D.Double(x, y), (float) Math.max(gr, 0.5),
                    new float[]{0f, 0.45f, 1f},
                    new Color[]{Theme.rgba(col, alpha), Theme.rgba(col, alpha * 0.5), Theme.rgba(col, 0)}));
            g.fill(circle(x, y, gr));
            if (rr > 2.5) {
                g.setPaint(new RadialGradientPaint(new Point2D.Double(x - 0.35 * rr, y - 0.35 * rr), (float) (1.35 * rr),
                        new float[]{0f, 0.5f, 1f},
                        new Color[]{new Color(mix(core, 0xFFFFFF, 0.55)), new Color(core), new Color(mix(core, 0x000000, 0.45))}));
            } else {
                g.setColor(new Color(core));
            }
            g.fill(circle(x, y, rr));
            return;
        }
        AffineTransform old = g.getTransform();
        g.translate(x, y);
        g.transform(new AffineTransform(d[0], d[1], -d[1], d[0], 0, 0));
        g.scale(d[2], d[3]);
        g.setPaint(new RadialGradientPaint(new Point2D.Double(0, 0), (float) Math.max(gr, 0.5),
                new float[]{0f, 0.45f, 1f},
                new Color[]{Theme.rgba(col, alpha), Theme.rgba(col, alpha * 0.5), Theme.rgba(col, 0)}));
        g.fill(circle(0, 0, gr));
        if (rr > 2.5) {
            g.setPaint(new RadialGradientPaint(new Point2D.Double(-0.35 * rr, -0.35 * rr), (float) (1.35 * rr),
                    new float[]{0f, 0.5f, 1f},
                    new Color[]{new Color(mix(core, 0xFFFFFF, 0.55)), new Color(core), new Color(mix(core, 0x000000, 0.45))}));
        } else {
            g.setColor(new Color(core));
        }
        g.fill(circle(0, 0, rr));
        g.setTransform(old);
    }

    private BufferedImage dots, glImg;
    private int[] glPx;

    /**
     * Следы и тела шейдерами ({@link GlScene}): те же цвета, свечение, объём и дымка, что в drawBodies
     * и drawTrails, но свечение и объём — у всех тел, и в больших сценах тоже. Тела проецируются здесь
     * (в double) и идут от дальних к ближним; следы проецирует видеокарта. false — OpenGL отказал,
     * рисует Java2D.
     */
    private boolean drawGl(Graphics2D g) {
        GlScene gl = GlScene.get();
        int W = getWidth(), H = getHeight();
        if (gl == null) return false;
        if (W <= 0 || H <= 0) return true;
        try {
            List<Body> parts = world.parts;
            int n = parts.size(), m = 0;
            double[][] proj = new double[n][];
            long[] keys = new long[n];
            for (int i = 0; i < n; i++) {
                double[] sc = cam.project(parts.get(i).p);
                if (sc == null) continue;
                proj[i] = sc;
                keys[m++] = (long) Float.floatToIntBits((float) sc[2]) << 32 | i; // глубина > 0: порядок битов = порядок чисел
            }
            java.util.Arrays.sort(keys, 0, m);
            // в режиме «поле» тела рисует Java2D-слой облаков поверх (drawClouds); шейдеры — только следы
            boolean balls = !s.bodyField;
            gl.beginBodies(balls ? m : 0);
            if (balls) for (int k = m - 1; k >= 0; k--) {
                int i = (int) keys[k];
                Body b = parts.get(i);
                double[] sc = proj[i];
                double f = fog(sc[2]);
                double[] dg = diskGlow(b, sc[3], f);
                double rr = dg[0], gr = Math.max(dg[1], 0.5);
                double pad = Math.max(rr, gr) * (s.deform == Settings.DeformMode.OFF ? 1 : DEFORM_PAD);
                if (sc[0] + pad < 0 || sc[0] - pad > W || sc[1] + pad < 0 || sc[1] - pad > H) continue;
                double[] df = deformOf(b, i, cam);
                gl.body(sc[0], sc[1], rr, gr, bodyColor(b), bodyAlpha(b) * f, mix(bodyCore(b), BG_RGB, 1 - f), rr > 2.5,
                        df[0], df[1], df[2], df[3]);
            }
            int pts = 0, trails = 0;
            if (s.showTrail) {
                for (Body b : parts) {
                    int c = b.trail.size();
                    if (c >= 2) {
                        pts += c;
                        trails++;
                    }
                }
            }
            gl.beginTrails(pts, trails);
            if (trails > 0) {
                double len = Formula.TRAIL_LEN;
                for (Body b : parts) {
                    Trail tr = b.trail;
                    int c = tr.size();
                    if (c < 2) continue;
                    gl.trail(c);
                    for (int i = 0; i < c; i++) {
                        double bright = Math.max(0, 1 - (c - 1 - i) / len);
                        gl.point(tr.x(i) - cam.tx, tr.y(i) - cam.ty, tr.z(i) - cam.tz, bright, b.trailColor);
                    }
                }
            }
            if (glImg == null || glImg.getWidth() != W || glImg.getHeight() != H) {
                glImg = new BufferedImage(W, H, BufferedImage.TYPE_INT_ARGB_PRE);
                glPx = ((DataBufferInt) glImg.getRaster().getDataBuffer()).getData();
            }
            gl.render(W, H, glPx, Math.cos(cam.yaw), Math.sin(cam.yaw), Math.cos(cam.pitch), Math.sin(cam.pitch), cam.focal, cam.camR,
                    cam.camR - sceneL, 2 * sceneL, n > LARGE ? 1f : 1.5f);
            g.drawImage(glImg, 0, 0, null);
            return true;
        } catch (Throwable e) {
            GlScene.fail(e);
            return false;
        }
    }

    /**
     * Большая сцена: шары без свечения и объёма. Мелкие (до 3 пикселей) пишутся прямо в пиксели
     * одной картинки — 10 000 вызовов рисования стоили ~25 мс, запись пикселей — меньше 1 мс;
     * крупные (близкие) — поверх, сглаженными кругами, от дальних к ближним.
     */
    private void drawDots(Graphics2D g, List<Body> bodies, List<double[]> proj, Integer[] order, List<Integer> idx) {
        int W = getWidth(), H = getHeight();
        if (W <= 0 || H <= 0) return;
        if (dots == null || dots.getWidth() != W || dots.getHeight() != H) dots = new BufferedImage(W, H, BufferedImage.TYPE_INT_ARGB_PRE);
        int[] px = ((DataBufferInt) dots.getRaster().getDataBuffer()).getData();
        java.util.Arrays.fill(px, 0);
        List<Integer> big = new java.util.ArrayList<>();
        for (int i : order) {
            Body b = bodies.get(i);
            double[] sc = proj.get(i);
            double[] dg = diskGlow(b, sc[3], fog(sc[2]));
            // диск — true rad; для STAR/PLANET/MOON ореол (haloPx) даёт заметную точку без раздувания rad
            double rs = Math.max(dg[0], b.astro.halo ? dg[1] * 0.35 : dg[0]);
            if (sc[0] + rs < 0 || sc[0] - rs > W || sc[1] + rs < 0 || sc[1] - rs > H) continue;
            if (rs >= 3) {
                big.add(i);
                continue;
            }
            int argb = 0xFF000000 | mix(bodyColor(b), BG_RGB, 1 - fog(sc[2]));
            double r2 = rs * rs;
            for (int y = Math.max(0, (int) Math.floor(sc[1] - rs)), y1 = Math.min(H, (int) Math.ceil(sc[1] + rs)); y < y1; y++) {
                double dy = y + 0.5 - sc[1];
                for (int x = Math.max(0, (int) Math.floor(sc[0] - rs)), x1 = Math.min(W, (int) Math.ceil(sc[0] + rs)); x < x1; x++) {
                    double dx = x + 0.5 - sc[0];
                    if (dx * dx + dy * dy <= r2) px[y * W + x] = argb;
                }
            }
        }
        g.drawImage(dots, 0, 0, null);
        for (int i : big) {
            Body b = bodies.get(i);
            double[] sc = proj.get(i);
            double[] dg = diskGlow(b, sc[3], fog(sc[2]));
            g.setColor(new Color(mix(bodyColor(b), BG_RGB, 1 - fog(sc[2]))));
            double r = Math.max(dg[0], b.astro.halo ? dg[1] * 0.35 : dg[0]);
            fillDisk(g, sc[0], sc[1], r, deformOf(b, idx.get(i), cam));
        }
    }

    /** Тонкая линия между полюсами диполя — только показывает пару; силы — в pairQN. */
    private void drawDipoles(Graphics2D g) {
        g.setStroke(new BasicStroke(1.2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        for (Body b : world.parts) {
            if (b.kind != Species.DP || b.partner == null) continue;
            double[] A = cam.project(b.p), B = cam.project(b.partner.p);
            if (A == null || B == null) continue;
            double f = fog(0.5 * (A[2] + B[2]));
            g.setPaint(new GradientPaint((float) A[0], (float) A[1], Theme.rgba(Species.DP.color, 0.7 * f),
                    (float) B[0], (float) B[1], Theme.rgba(Species.DM.color, 0.7 * f)));
            g.draw(new Line2D.Double(A[0], A[1], B[0], B[1]));
        }
    }

    /**
     * Большие сцены: с LARGE тел — шары без свечения и без теней на полу, с LABELS_MAX — без
     * подписей, с FORCES_MAX — без стрелок сил; облака поля и облака частиц рисуются при любом числе тел.
     * (тысячи пятен за кадр).
     */
    static final int LARGE = 400, LABELS_MAX = 100, FORCES_MAX = 500;

    static final int REP_RGB = 0xFF5A5A, KUO_RGB = 0x5AC8FF, SUM_RGB = 0xFFFFFF;

    /**
     * «полюса как стороны» — у <b>каждого</b> тела две встречные составляющие (как у КУО / диполя):
     * красная — частное отталкивание (и отталкивание полюсов), голубая — приталкивание от КУО
     * (и прижатие полюсов). Тот же split, что {@link #drawForces}, без белого итога — акцент на
     * «толчок с двух сторон». Только картинка; Forces / pairQN не меняет.
     */
    private void drawPolesSides(Graphics2D g) {
        int n = world.parts.size();
        double[][] rep = new double[n][3], kuo = new double[n][3], sum = new double[n][3];
        boolean kuoPath = world.mode == World.Mode.KUO;
        boolean showPush = kuoPath && world.kuo.on;
        world.kuo.compute(kuoPath ? world.kuo.beta : 0, kuoPath ? world.kuo.gain() : 1, rep, kuo, sum);
        if (kuoPath && !world.kuo.on) {
            for (int i = 0; i < n; i++) {
                kuo[i][0] = kuo[i][1] = kuo[i][2] = 0;
                sum[i][0] = rep[i][0];
                sum[i][1] = rep[i][1];
                sum[i][2] = rep[i][2];
            }
        } else if (showPush && world.kuo.closed) {
            double s = 1 - world.kuo.q();
            for (int i = 0; i < n; i++) {
                for (int k = 0; k < 3; k++) {
                    kuo[i][k] *= s;
                    sum[i][k] = rep[i][k] + kuo[i][k];
                }
            }
        }
        double max = 0, maxSum = 0;
        for (int i = 0; i < n; i++) {
            maxSum = Math.max(maxSum, len(sum[i]));
            max = Math.max(max, Math.max(len(sum[i]), Math.max(len(rep[i]), len(kuo[i]))));
        }
        if (!(max > 0)) return;
        decades = Math.max(3, maxSum > 0 ? Math.log10(max / maxSum) + 1.2 : 3);
        double L = 0.35 * sceneL;
        for (int i = 0; i < n; i++) {
            Body b = world.parts.get(i);
            double[] sc = cam.project(b.p);
            if (sc == null) continue;
            double f = fog(sc[2]);
            arrow(g, b.p, rep[i], max, L, REP_RGB, 0.9 * f, 1.8f);
            if (showPush) arrow(g, b.p, kuo[i], max, L, KUO_RGB, 0.9 * f, 1.8f);
        }
    }

    /**
     * Стрелки сил у каждой частицы: белая — итог (тяготение между телами). В комплексном тяготении
     * ещё красная — частное отталкивание тел и голубая — приталкивание от КУО. Длина по log-шкале
     * от самой большой суммы на сцене, чтобы e и p были видны вместе; концы проецируются в 3D.
     */
    private void drawForces(Graphics2D g) {
        int n = world.parts.size();
        double[][] rep = new double[n][3], kuo = new double[n][3], sum = new double[n][3];
        boolean kuoPath = world.mode == World.Mode.KUO;
        boolean showPush = kuoPath && world.kuo.on;
        world.kuo.compute(kuoPath ? world.kuo.beta : 0, kuoPath ? world.kuo.gain() : 1, rep, kuo, sum);
        if (kuoPath && !world.kuo.on) { // только −β·W: без голубого push
            for (int i = 0; i < n; i++) {
                kuo[i][0] = kuo[i][1] = kuo[i][2] = 0;
                sum[i][0] = rep[i][0];
                sum[i][1] = rep[i][1];
                sum[i][2] = rep[i][2];
            }
        } else if (showPush && world.kuo.closed) { // замкнутый КУО: приталкивание ×(1 − q)
            double s = 1 - world.kuo.q();
            for (int i = 0; i < n; i++) {
                for (int k = 0; k < 3; k++) {
                    kuo[i][k] *= s;
                    sum[i][k] = rep[i][k] + kuo[i][k];
                }
            }
        }
        double max = 0, maxSum = 0;
        for (int i = 0; i < n; i++) {
            maxSum = Math.max(maxSum, len(sum[i]));
            max = Math.max(max, Math.max(len(sum[i]), showPush ? Math.max(len(rep[i]), len(kuo[i])) : len(rep[i])));
        }
        if (!(maxSum > 0)) return;
        // шкала — от самой длинной стрелки; при большой β составляющие на порядки длиннее итога,
        // поэтому декад на шкале столько, чтобы итог оставался виден
        decades = Math.max(3, Math.log10(max / maxSum) + 1.2);
        double L = 0.35 * sceneL; // длина самой большой стрелки в мире
        for (int i = 0; i < n; i++) {
            Body b = world.parts.get(i);
            double[] sc = cam.project(b.p);
            if (sc == null) continue;
            double f = fog(sc[2]);
            if (kuoPath) {
                arrow(g, b.p, rep[i], max, L, REP_RGB, 0.85 * f, 1.5f);
                if (showPush) arrow(g, b.p, kuo[i], max, L, KUO_RGB, 0.85 * f, 1.5f);
            }
            arrow(g, b.p, sum[i], max, L, SUM_RGB, 0.9 * f, 2f);
        }
    }

    private static double len(double[] v) {
        return Math.sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2]);
    }

    /**
     * Стабилизация: мини-крафт с анти-тяги и полевой луч вдоль ускорения коррекции.
     * Цвет отдельно от G-поля (бирюза), чтобы не путать с «поле».
     */
    private static final int STAB_RGB = 0x5CE1C5;
    /** Приливы к экватору: приглушённый холодный тон (не конкурирует с силами/стабилизацией). */
    private static final int TIDE_RGB = 0x8AA4B5;

    /**
     * Option B оверлей: полупрозрачный диск экватора родителя + тонкие метки от спутника
     * к плоскости (направление демпфирования). Только при {@link World#tidalEquator}.
     * Рисуется Java2D поверх шейдеров — как КУО / полюса / стабилизация.
     */
    private void drawTidalEquator(Graphics2D g) {
        if (world.tideEquatorN.isEmpty()) world.refreshTideOverlay();
        if (world.tideEquatorN.isEmpty()) return;
        final int RING = 64;
        g.setStroke(new BasicStroke(1.2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        for (Map.Entry<Body, double[]> e : world.tideEquatorN.entrySet()) {
            Body hub = e.getKey();
            if (!world.parts.contains(hub)) continue;
            double[] n = e.getValue();
            Double diskObj = world.tideDiskR.get(hub);
            double diskR = diskObj != null ? diskObj : hub.rad * 12;
            double[] ex = tideBasisX(n);
            double[] ey = {
                    n[1] * ex[2] - n[2] * ex[1],
                    n[2] * ex[0] - n[0] * ex[2],
                    n[0] * ex[1] - n[1] * ex[0]
            };
            double el = Math.sqrt(ey[0] * ey[0] + ey[1] * ey[1] + ey[2] * ey[2]);
            if (el < 1e-12) continue;
            ey[0] /= el;
            ey[1] /= el;
            ey[2] /= el;

            Path2D.Double ring = new Path2D.Double();
            Path2D.Double fill = new Path2D.Double();
            boolean pen = false;
            for (int i = 0; i <= RING; i++) {
                double a = 2 * Math.PI * i / RING;
                double ca = Math.cos(a), sa = Math.sin(a);
                double x = hub.p[0] + diskR * (ca * ex[0] + sa * ey[0]);
                double y = hub.p[1] + diskR * (ca * ex[1] + sa * ey[1]);
                double z = hub.p[2] + diskR * (ca * ex[2] + sa * ey[2]);
                double[] sc = cam.project(x, y, z);
                if (sc == null) {
                    pen = false;
                    continue;
                }
                if (!pen) {
                    ring.moveTo(sc[0], sc[1]);
                    fill.moveTo(sc[0], sc[1]);
                    pen = true;
                } else {
                    ring.lineTo(sc[0], sc[1]);
                    fill.lineTo(sc[0], sc[1]);
                }
            }
            if (pen) {
                fill.closePath();
                g.setColor(Theme.rgba(TIDE_RGB, 0.06));
                g.fill(fill);
                g.setColor(Theme.rgba(TIDE_RGB, 0.28));
                g.draw(ring);
                // внутреннее кольцо у поверхности hub — якорь экватора
                Path2D.Double inner = new Path2D.Double();
                double ri = Math.min(diskR * 0.22, hub.rad * 3.5);
                boolean ip = false;
                for (int i = 0; i <= RING; i++) {
                    double a = 2 * Math.PI * i / RING;
                    double ca = Math.cos(a), sa = Math.sin(a);
                    double x = hub.p[0] + ri * (ca * ex[0] + sa * ey[0]);
                    double y = hub.p[1] + ri * (ca * ex[1] + sa * ey[1]);
                    double z = hub.p[2] + ri * (ca * ex[2] + sa * ey[2]);
                    double[] sc = cam.project(x, y, z);
                    if (sc == null) {
                        ip = false;
                        continue;
                    }
                    if (!ip) {
                        inner.moveTo(sc[0], sc[1]);
                        ip = true;
                    } else inner.lineTo(sc[0], sc[1]);
                }
                g.setColor(Theme.rgba(TIDE_RGB, 0.35));
                g.draw(inner);
            }

            // метки: луна → проекция на экватор (направление гашения v⊥); планеты к звезде — без тиков
            g.setStroke(new BasicStroke(1f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            for (Body sat : world.parts) {
                if (sat == hub || sat.m >= hub.m) continue;
                if (sat.astro != AstroClass.MOON) continue;
                double rx = sat.p[0] - hub.p[0], ry = sat.p[1] - hub.p[1], rz = sat.p[2] - hub.p[2];
                double height = rx * n[0] + ry * n[1] + rz * n[2];
                if (Math.abs(height) < hub.rad * 0.02) continue;
                double rxy2 = rx * rx + ry * ry + rz * rz - height * height;
                if (rxy2 < 0) rxy2 = 0;
                double rho = Math.sqrt(rxy2);
                if (rho > diskR * 1.25) continue;
                double[] foot = {
                        sat.p[0] - height * n[0],
                        sat.p[1] - height * n[1],
                        sat.p[2] - height * n[2]
                };
                double[] A = cam.project(sat.p), B = cam.project(foot);
                if (A == null || B == null) continue;
                double f = fog(A[2]);
                g.setColor(Theme.rgba(TIDE_RGB, 0.22 * f));
                g.draw(new Line2D.Double(A[0], A[1], B[0], B[1]));
                // короткий «тик» на плоскости
                double tick = Math.max(hub.rad * 0.35, diskR * 0.03);
                double[] t0 = cam.project(foot[0] - tick * ex[0], foot[1] - tick * ex[1], foot[2] - tick * ex[2]);
                double[] t1 = cam.project(foot[0] + tick * ex[0], foot[1] + tick * ex[1], foot[2] + tick * ex[2]);
                if (t0 != null && t1 != null) {
                    g.setColor(Theme.rgba(TIDE_RGB, 0.30 * f));
                    g.draw(new Line2D.Double(t0[0], t0[1], t1[0], t1[1]));
                }
            }
        }
    }

    /** Ортонормаль в плоскости с нормалью n (запасная ось, если n ‖ x). */
    private static double[] tideBasisX(double[] n) {
        double[] a = Math.abs(n[0]) < 0.9 ? new double[]{1, 0, 0} : new double[]{0, 1, 0};
        double[] ex = {
                n[1] * a[2] - n[2] * a[1],
                n[2] * a[0] - n[0] * a[2],
                n[0] * a[1] - n[1] * a[0]
        };
        double l = Math.sqrt(ex[0] * ex[0] + ex[1] * ex[1] + ex[2] * ex[2]);
        if (l < 1e-12) return new double[]{1, 0, 0};
        return new double[]{ex[0] / l, ex[1] / l, ex[2] / l};
    }

    private void drawStabilize(Graphics2D g) {
        Body b = world.stabilize;
        if (b == null || !world.parts.contains(b)) return;
        double[] thr = world.stabilizeThrust;
        double tm = len(thr);
        double dx, dy, dz;
        if (tm > 1e-12) {
            dx = thr[0] / tm;
            dy = thr[1] / tm;
            dz = thr[2] / tm;
        } else if (world.stabilizeCentral != null) {
            // нет тяги — крафт снаружи по радиусу от централа
            Body c = world.stabilizeCentral;
            dx = b.p[0] - c.p[0];
            dy = b.p[1] - c.p[1];
            dz = b.p[2] - c.p[2];
            double rl = Math.sqrt(dx * dx + dy * dy + dz * dz);
            if (rl < 1e-12) return;
            dx /= rl;
            dy /= rl;
            dz /= rl;
        } else {
            return;
        }
        double off = Math.max(b.rad * 1.85, b.rad + 0.02);
        // крафт на стороне, противоположной тяге (сопло смотрит вдоль thr)
        double[] craft = {b.p[0] - dx * off, b.p[1] - dy * off, b.p[2] - dz * off};
        double[] nose = {b.p[0] - dx * (off * 0.55), b.p[1] - dy * (off * 0.55), b.p[2] - dz * (off * 0.55)};
        double[] beamTip = {b.p[0] + dx * (b.rad * 1.35 + off * 0.35),
                b.p[1] + dy * (b.rad * 1.35 + off * 0.35),
                b.p[2] + dz * (b.rad * 1.35 + off * 0.35)};
        double[] scC = cam.project(craft), scN = cam.project(nose), scT = cam.project(beamTip);
        double[] scB = cam.project(b.p);
        if (scC == null || scB == null) return;
        double alpha = 0.35 + 0.55 * world.stabilizePower;
        // луч поля: от крафта через тело наружу
        if (scT != null) {
            g.setStroke(new BasicStroke(2.4f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            g.setColor(Theme.rgba(STAB_RGB, 0.25 * alpha));
            g.draw(new Line2D.Double(scC[0], scC[1], scT[0], scT[1]));
            g.setStroke(new BasicStroke(1.2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            g.setColor(Theme.rgba(STAB_RGB, 0.85 * alpha));
            g.draw(new Line2D.Double(scC[0], scC[1], scT[0], scT[1]));
        }
        // мини-крафт: треугольник носом к телу (вдоль тяги)
        if (scN != null) {
            double px = scN[0] - scC[0], py = scN[1] - scC[1];
            double d = Math.hypot(px, py);
            if (d < 2) {
                px = scB[0] - scC[0];
                py = scB[1] - scC[1];
                d = Math.hypot(px, py);
            }
            if (d >= 2) {
                double hx = px / d, hy = py / d;
                double s = Math.max(5, Math.min(11, 0.35 * b.rad * scC[3] + 5));
                Path2D.Double ship = new Path2D.Double();
                ship.moveTo(scC[0] + hx * s, scC[1] + hy * s);
                ship.lineTo(scC[0] - hx * s * 0.7 + hy * s * 0.55, scC[1] - hy * s * 0.7 - hx * s * 0.55);
                ship.lineTo(scC[0] - hx * s * 0.7 - hy * s * 0.55, scC[1] - hy * s * 0.7 + hx * s * 0.55);
                ship.closePath();
                g.setColor(Theme.rgba(STAB_RGB, 0.9));
                g.fill(ship);
                g.setStroke(new BasicStroke(1f));
                g.setColor(Theme.rgba(0xE8FFF8, 0.85));
                g.draw(ship);
            }
        }
        // тонкая связь к централу
        Body c = world.stabilizeCentral;
        if (c != null && world.parts.contains(c)) {
            double[] scCen = cam.project(c.p);
            if (scCen != null) {
                g.setStroke(new BasicStroke(1f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER, 10f, new float[]{3, 5}, 0));
                g.setColor(Theme.rgba(STAB_RGB, 0.28));
                g.draw(new Line2D.Double(scB[0], scB[1], scCen[0], scCen[1]));
            }
        }
    }

    /** Стрелка от точки p вдоль a; длина L·(1 + log₁₀(|a|/max)/3) — три декады до нуля. */
    /** Сколько декад силы укладывается в длину стрелки (log-шкала); ставит drawForces. */
    private double decades = 3;

    private void arrow(Graphics2D g, double[] p, double[] a, double max, double L, int rgb, double alpha, float width) {
        double m = len(a);
        if (!(m > 0)) return;
        double t = 1 + Math.log10(m / max) / decades;
        if (t <= 0.05) return;
        double l = L * t;
        double[] end = {p[0] + a[0] / m * l, p[1] + a[1] / m * l, p[2] + a[2] / m * l};
        double[] A = cam.project(p), B = cam.project(end);
        if (A == null || B == null) return;
        double dx = B[0] - A[0], dy = B[1] - A[1], d = Math.hypot(dx, dy);
        if (d < 3) return;
        g.setColor(Theme.rgba(rgb, alpha));
        g.setStroke(new BasicStroke(width, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g.draw(new Line2D.Double(A[0], A[1], B[0], B[1]));
        double hx = dx / d, hy = dy / d, h = Math.min(8, 0.3 * d);
        Path2D.Double head = new Path2D.Double();
        head.moveTo(B[0], B[1]);
        head.lineTo(B[0] - h * hx + 0.5 * h * hy, B[1] - h * hy - 0.5 * h * hx);
        head.lineTo(B[0] - h * hx - 0.5 * h * hy, B[1] - h * hy + 0.5 * h * hx);
        head.closePath();
        g.fill(head);
    }

    private static final java.awt.Font LABEL_FONT = Theme.UI_FONT.deriveFont(13f);
    private static final java.awt.Font MASS_FONT = Theme.UI_FONT.deriveFont(11f);

    /** Масса для подписи: 1.0, 1836, 1.0e+6 — коротко. */
    static String massText(double m) {
        return m >= 1e4 || m < 0.01 ? Theme.exp(m, 2) : Theme.fixed(m, m < 10 ? 1 : 0);
    }

    private void drawLabels(Graphics2D g) {
        for (Body b : world.parts) {
            double[] sc = cam.project(b.p);
            if (sc == null) continue;
            double dy = Math.max(12, b.rad * sc[3] + 10);
            float x = (float) (sc[0] - 6), y = (float) (sc[1] - dy);
            g.setFont(LABEL_FONT);
            g.setColor(Theme.LABEL);
            g.drawString(b.kind.label, x, y);
            int w = g.getFontMetrics().stringWidth(b.kind.label);
            g.setFont(MASS_FONT);
            g.setColor(Theme.MUTED);
            g.drawString(massText(b.N), x + w + 5, y - 1);
        }
    }

    /** Сводка слева сверху; «формула и N = V·q» раскрывает подробности по клику. */
    private void drawOverlay(Graphics2D g) {
        g.setFont(Theme.UI_FONT.deriveFont(13.5f)); // тот же читаемый шрифт, что в панели
        FontMetrics fm = g.getFontMetrics();
        int lh = Math.round(13.5f * 1.5f), x = 12, y = 12 + fm.getAscent();
        for (String line : summary) {
            shadowText(g, line, x, y, Theme.TEXT);
            y += lh;
        }
        y += 6;
        String toggle = (s.detailsOpen ? "− " : "+ ") + "формула и N = V·q";
        shadowText(g, toggle, x, y, Theme.MUTED);
        detailsToggle.setBounds(x, y - fm.getAscent(), fm.stringWidth(toggle), fm.getHeight());
        if (!s.detailsOpen) return;
        y += lh;
        for (String line : details) {
            shadowText(g, line, x, y, Theme.MUTED);
            y += lh;
        }
    }

    private static void shadowText(Graphics2D g, String text, int x, int y, Color color) {
        g.setColor(Color.BLACK);
        g.drawString(text, x + 1, y + 1);
        g.setColor(color);
        g.drawString(text, x, y);
    }

    /** Смесь цветов a → b в доле t. */
    private static int mix(int a, int b, double t) {
        int r = 0;
        for (int sh = 16; sh >= 0; sh -= 8) {
            double ca = (a >> sh) & 255, cb = (b >> sh) & 255;
            r |= (int) Math.round(ca + (cb - ca) * t) << sh;
        }
        return r;
    }

    private static Ellipse2D circle(double x, double y, double r) {
        return new Ellipse2D.Double(x - r, y - r, 2 * r, 2 * r);
    }
}
