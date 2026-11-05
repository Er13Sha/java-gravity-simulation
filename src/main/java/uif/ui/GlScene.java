package uif.ui;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemoryLayout;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.invoke.MethodHandle;
import java.nio.charset.StandardCharsets;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_BYTE;
import static java.lang.foreign.ValueLayout.JAVA_FLOAT;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;
import static java.lang.foreign.ValueLayout.JAVA_SHORT;

/**
 * Рисование шейдерами OpenGL через FFM (opengl32.dll, без сторонних библиотек): тела — круги-импостеры
 * со свечением и объёмом, следы — линии с 4× сглаживанием (MSAA). Картинка рисуется во внеэкранный
 * буфер (FBO) скрытого окна и читается в int[] — Swing кладёт её поверх стенки и под подписи.
 * <p>
 * Координаты экрана — как у Java2D (y вниз): строка 0 буфера OpenGL — нижняя, а NDC y = −1 здесь —
 * верх экрана, так что glReadPixels отдаёт строки сразу сверху вниз. Цвета — premultiplied ARGB,
 * как TYPE_INT_ARGB_PRE. Вызывается из одного потока за раз; контекст делается текущим на кадр.
 */
final class GlScene {
    private static final Linker L = Linker.nativeLinker();
    private static GlScene instance;
    private static String failure;

    /** Единственный экземпляр или null (тогда {@link #failure()} — причина). */
    static synchronized GlScene get() {
        if (instance == null && failure == null) {
            try {
                instance = new GlScene();
            } catch (Throwable e) {
                failure = e.getMessage() != null ? e.getMessage() : e.toString();
            }
        }
        return instance;
    }

    static synchronized String failure() {
        return failure;
    }

    static synchronized boolean failed() {
        return failure != null;
    }

    /** Видеокарта и сглаживание, причина отказа или null — ещё не пробовали. */
    static synchronized String status() {
        return failure != null ? failure : instance != null ? instance.renderer() : null;
    }

    /** Ошибка во время кадра: шейдеры отключаются до перезапуска, рисует Java2D. */
    static synchronized void fail(Throwable e) {
        failure = "ошибка OpenGL: " + (e.getMessage() != null ? e.getMessage() : e.toString());
        if (instance != null) instance.releasePixels();
        instance = null;
    }

    private static final int GL_COLOR_BUFFER_BIT = 0x4000, GL_BLEND = 0x0BE2, GL_ONE = 1, GL_ONE_MINUS_SRC_ALPHA = 0x0303,
            GL_TRIANGLE_STRIP = 5, GL_LINE_STRIP = 3, GL_FLOAT = 0x1406, GL_UNSIGNED_BYTE = 0x1401, GL_ARRAY_BUFFER = 0x8892,
            GL_STREAM_DRAW = 0x88E0, GL_STATIC_DRAW = 0x88E4, GL_VERTEX_SHADER = 0x8B31, GL_FRAGMENT_SHADER = 0x8B30,
            GL_COMPILE_STATUS = 0x8B81, GL_LINK_STATUS = 0x8B82, GL_FRAMEBUFFER = 0x8D40, GL_READ_FRAMEBUFFER = 0x8CA8,
            GL_DRAW_FRAMEBUFFER = 0x8CA9, GL_RENDERBUFFER = 0x8D41, GL_RGBA8 = 0x8058, GL_COLOR_ATTACHMENT0 = 0x8CE0,
            GL_FRAMEBUFFER_COMPLETE = 0x8CD5, GL_NEAREST = 0x2600, GL_BGRA = 0x80E1, GL_UNSIGNED_INT_8_8_8_8_REV = 0x8367,
            GL_PACK_ALIGNMENT = 0x0D05, GL_MULTISAMPLE = 0x809D, GL_RENDERER = 0x1F01, GL_VERSION = 0x1F02, GL_MAX_SAMPLES = 0x8D57;

    /**
     * Тело: угол квадрата (на вершину); x, y, rr, gr; свечение rgb + a; ядро rgb + объём;
     * deform = (cos θ, sin θ, flat, bulge) — oblate «апельсин»: сжатие вдоль оси силы, экваториальный раздув.
     */
    private static final String BODY_VS = """
            #version 330
            layout(location = 0) in vec2 corner;
            layout(location = 1) in vec4 geo;
            layout(location = 2) in vec4 glow;
            layout(location = 3) in vec4 core;
            layout(location = 4) in vec4 deform;
            uniform vec2 view;
            out vec2 p;
            flat out float rr, gr;
            flat out vec4 gc, cc;
            void main() {
                float pol = deform.z, eq = deform.w; // polar flatten / equatorial bulge (oblate)
                float R = max(geo.z, geo.w) + 1.0;
                // угол quad в «логических» координатах диска (для FS length(p))
                p = corner * R;
                // экран: сплющить вдоль (cos,sin), раздуть поперёк — апельсин, не яйцо
                float u = p.x * deform.x + p.y * deform.y;
                float v = -p.x * deform.y + p.y * deform.x;
                vec2 q = vec2(u * pol * deform.x - v * eq * deform.y,
                             u * pol * deform.y + v * eq * deform.x);
                vec2 s = geo.xy + q;
                gl_Position = vec4(s / view * 2.0 - 1.0, 0.0, 1.0);
                rr = geo.z; gr = geo.w; gc = glow; cc = core;
            }
            """;

    /** Как RadialGradientPaint в ScenePanel.drawBodies: свечение 1 → ½ → 0 на 0, 0.45, 1; ядро — блик, цвет, тень. */
    private static final String BODY_FS = """
            #version 330
            in vec2 p;
            flat in float rr, gr;
            flat in vec4 gc, cc;
            out vec4 o;
            void main() {
                float d = length(p), u = d / gr;
                float ga = u < 0.45 ? mix(1.0, 0.5, u / 0.45) : mix(0.5, 0.0, min(1.0, (u - 0.45) / 0.55));
                ga *= gc.a;
                float ca = clamp(rr - d + 0.5, 0.0, 1.0);
                vec3 c = cc.rgb;
                if (cc.a > 0.5) {
                    float t = length(p + vec2(0.35 * rr)) / (1.35 * rr);
                    vec3 hi = mix(c, vec3(1.0), 0.55), lo = mix(c, vec3(0.0), 0.45);
                    c = t < 0.5 ? mix(hi, c, t / 0.5) : mix(c, lo, min(1.0, (t - 0.5) / 0.5));
                }
                o = vec4(c * ca, ca) + vec4(gc.rgb * ga, ga) * (1.0 - ca);
                if (o.a <= 0.0) discard;
            }
            """;

    /**
     * След: точка относительно цели камеры, яркость по возрасту, цвет тела. Поворот и перспектива — как
     * Camera.project; w = глубина, поэтому отрезки через ближнюю плоскость обрезаются, а не пропадают.
     */
    private static final String TRAIL_VS = """
            #version 330
            layout(location = 0) in vec3 pos;
            layout(location = 1) in float bright;
            layout(location = 2) in vec4 col;
            uniform vec2 view;
            uniform vec4 cam;   // cos yaw, sin yaw, cos pitch, sin pitch
            uniform vec4 lens;  // focal, camR, ближний край дымки, 2L
            out vec4 c;
            void main() {
                float x1 = pos.x * cam.x - pos.z * cam.y, z1 = pos.x * cam.y + pos.z * cam.x;
                float y2 = pos.y * cam.z - z1 * cam.w, z2 = pos.y * cam.w + z1 * cam.z;
                float zc = z2 + lens.y;
                gl_Position = vec4(x1 * lens.x * 2.0 / view.x, -y2 * lens.x * 2.0 / view.y, zc - 0.01, zc);
                float fog = clamp(1.0 - 0.55 * (zc - lens.z) / lens.w, 0.45, 1.0);
                float a = 0.35 * fog * bright;
                c = vec4(col.rgb * a, a);
            }
            """;

    private static final String TRAIL_FS = """
            #version 330
            in vec4 c;
            out vec4 o;
            void main() { o = c; }
            """;

    private final Arena arena = Arena.ofShared();
    private Arena pixelArena;
    private final MethodHandle wglMakeCurrent, glViewport, glClearColor, glClear, glEnable, glBlendFunc, glReadPixels,
            glLineWidth, glGetError, glPixelStorei;
    private final MethodHandle glUseProgram, glUniform2f, glUniform4f, glBindBuffer, glBufferData, glBindVertexArray,
            glDrawArraysInstanced, glMultiDrawArrays, glBindFramebuffer, glBlitFramebuffer, glBindRenderbuffer,
            glRenderbufferStorageMultisample, glRenderbufferStorage, glFramebufferRenderbuffer, glCheckFramebufferStatus,
            glGetUniformLocation;
    private final MemorySegment hdc, ctx;
    private final String renderer;
    private final int bodyProg, trailProg, bodyVao, trailVao, bodyBuf, trailBuf;
    private final int uBodyView, uTrailView, uCam, uLens;
    private final int msFbo, msRb, fbo, rb, samples;
    private int fw, fh;
    private MemorySegment inst, verts, pixels, firsts, counts;

    private GlScene() throws Throwable {
        SymbolLookup gl = SymbolLookup.libraryLookup("opengl32", arena), user = SymbolLookup.libraryLookup("user32", arena),
                gdi = SymbolLookup.libraryLookup("gdi32", arena), kern = SymbolLookup.libraryLookup("kernel32", arena);
        MethodHandle getModule = h(kern, "GetModuleHandleW", ADDRESS, ADDRESS);
        MethodHandle register = h(user, "RegisterClassExW", JAVA_SHORT, ADDRESS);
        MethodHandle create = h(user, "CreateWindowExW", ADDRESS, JAVA_INT, ADDRESS, ADDRESS, JAVA_INT,
                JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT, ADDRESS, ADDRESS, ADDRESS, ADDRESS);
        MethodHandle getDC = h(user, "GetDC", ADDRESS, ADDRESS);
        MethodHandle choose = h(gdi, "ChoosePixelFormat", JAVA_INT, ADDRESS, ADDRESS);
        MethodHandle setFormat = h(gdi, "SetPixelFormat", JAVA_INT, ADDRESS, JAVA_INT, ADDRESS);
        MethodHandle createCtx = h(gl, "wglCreateContext", ADDRESS, ADDRESS);
        wglMakeCurrent = h(gl, "wglMakeCurrent", JAVA_INT, ADDRESS, ADDRESS);
        MethodHandle getProc = h(gl, "wglGetProcAddress", ADDRESS, ADDRESS);
        MethodHandle getString = h(gl, "glGetString", ADDRESS, JAVA_INT);
        MethodHandle getInteger = v(gl, "glGetIntegerv", JAVA_INT, ADDRESS);
        glViewport = v(gl, "glViewport", JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT);
        glClearColor = v(gl, "glClearColor", JAVA_FLOAT, JAVA_FLOAT, JAVA_FLOAT, JAVA_FLOAT);
        glClear = v(gl, "glClear", JAVA_INT);
        glEnable = v(gl, "glEnable", JAVA_INT);
        glBlendFunc = v(gl, "glBlendFunc", JAVA_INT, JAVA_INT);
        glReadPixels = v(gl, "glReadPixels", JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT, ADDRESS);
        glLineWidth = v(gl, "glLineWidth", JAVA_FLOAT);
        glGetError = h(gl, "glGetError", JAVA_INT);
        glPixelStorei = v(gl, "glPixelStorei", JAVA_INT, JAVA_INT);

        // скрытое окно со своим DC — только чтобы получить контекст; рисуем во внеэкранный буфер
        MemorySegment hInst = (MemorySegment) getModule.invokeExact(MemorySegment.NULL);
        MemorySegment cls = wide("uif-gl"), wc = arena.allocate(80);
        wc.set(JAVA_INT, 0, 80);
        wc.set(JAVA_INT, 4, 0x20); // CS_OWNDC
        wc.set(ADDRESS, 8, user.find("DefWindowProcW").orElseThrow());
        wc.set(ADDRESS, 24, hInst);
        wc.set(ADDRESS, 64, cls);
        short atom = (short) register.invokeExact(wc);
        if (atom == 0) throw new RuntimeException("RegisterClassExW не удался");
        // родитель HWND_MESSAGE: окно только для сообщений — не попадает в широковещательные рассылки
        MemorySegment hwnd = (MemorySegment) create.invokeExact(0, cls, wide("uif"), 0, 0, 0, 16, 16,
                MemorySegment.ofAddress(-3L), MemorySegment.NULL, hInst, MemorySegment.NULL);
        if (hwnd.address() == 0) throw new RuntimeException("CreateWindowExW не удался");
        hdc = (MemorySegment) getDC.invokeExact(hwnd);
        MemorySegment pfd = arena.allocate(40);
        pfd.set(JAVA_SHORT, 0, (short) 40);
        pfd.set(JAVA_SHORT, 2, (short) 1);
        pfd.set(JAVA_INT, 4, 0x4 | 0x20 | 0x1); // DRAW_TO_WINDOW | SUPPORT_OPENGL | DOUBLEBUFFER
        pfd.set(JAVA_BYTE, 9, (byte) 32);
        pfd.set(JAVA_BYTE, 16, (byte) 8);
        int fmt = (int) choose.invokeExact(hdc, pfd);
        if (fmt == 0 || (int) setFormat.invokeExact(hdc, fmt, pfd) == 0) throw new RuntimeException("формат пикселей OpenGL не выбран");
        ctx = (MemorySegment) createCtx.invokeExact(hdc);
        if (ctx.address() == 0) throw new RuntimeException("wglCreateContext не удался");
        current(true);
        try {
            renderer = cstr((MemorySegment) getString.invokeExact(GL_RENDERER));
            String ver = cstr((MemorySegment) getString.invokeExact(GL_VERSION));
            String[] mm = ver.split("[ .]");
            int major = Integer.parseInt(mm[0]), minor = Integer.parseInt(mm[1]);
            if (major * 10 + minor < 33) throw new RuntimeException("нужен OpenGL 3.3, есть " + ver + " (" + renderer + ")");

            MethodHandle createShader = x(getProc, "glCreateShader", JAVA_INT, JAVA_INT);
            MethodHandle shaderSource = xv(getProc, "glShaderSource", JAVA_INT, JAVA_INT, ADDRESS, ADDRESS);
            MethodHandle compileShader = xv(getProc, "glCompileShader", JAVA_INT);
            MethodHandle getShaderiv = xv(getProc, "glGetShaderiv", JAVA_INT, JAVA_INT, ADDRESS);
            MethodHandle shaderLog = xv(getProc, "glGetShaderInfoLog", JAVA_INT, JAVA_INT, ADDRESS, ADDRESS);
            MethodHandle createProgram = x(getProc, "glCreateProgram", JAVA_INT);
            MethodHandle attach = xv(getProc, "glAttachShader", JAVA_INT, JAVA_INT);
            MethodHandle link = xv(getProc, "glLinkProgram", JAVA_INT);
            MethodHandle getProgramiv = xv(getProc, "glGetProgramiv", JAVA_INT, JAVA_INT, ADDRESS);
            MethodHandle programLog = xv(getProc, "glGetProgramInfoLog", JAVA_INT, JAVA_INT, ADDRESS, ADDRESS);
            MethodHandle genBuffers = xv(getProc, "glGenBuffers", JAVA_INT, ADDRESS);
            MethodHandle genArrays = xv(getProc, "glGenVertexArrays", JAVA_INT, ADDRESS);
            MethodHandle enableAttr = xv(getProc, "glEnableVertexAttribArray", JAVA_INT);
            MethodHandle attrPtr = xv(getProc, "glVertexAttribPointer", JAVA_INT, JAVA_INT, JAVA_INT, JAVA_BYTE, JAVA_INT, JAVA_LONG);
            MethodHandle divisor = xv(getProc, "glVertexAttribDivisor", JAVA_INT, JAVA_INT);
            MethodHandle genFbo = xv(getProc, "glGenFramebuffers", JAVA_INT, ADDRESS);
            MethodHandle genRb = xv(getProc, "glGenRenderbuffers", JAVA_INT, ADDRESS);
            glUseProgram = xv(getProc, "glUseProgram", JAVA_INT);
            glGetUniformLocation = x(getProc, "glGetUniformLocation", JAVA_INT, JAVA_INT, ADDRESS);
            glUniform2f = xv(getProc, "glUniform2f", JAVA_INT, JAVA_FLOAT, JAVA_FLOAT);
            glUniform4f = xv(getProc, "glUniform4f", JAVA_INT, JAVA_FLOAT, JAVA_FLOAT, JAVA_FLOAT, JAVA_FLOAT);
            glBindBuffer = xv(getProc, "glBindBuffer", JAVA_INT, JAVA_INT);
            glBufferData = xv(getProc, "glBufferData", JAVA_INT, JAVA_LONG, ADDRESS, JAVA_INT);
            glBindVertexArray = xv(getProc, "glBindVertexArray", JAVA_INT);
            glDrawArraysInstanced = xv(getProc, "glDrawArraysInstanced", JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT);
            glMultiDrawArrays = xv(getProc, "glMultiDrawArrays", JAVA_INT, ADDRESS, ADDRESS, JAVA_INT);
            glBindFramebuffer = xv(getProc, "glBindFramebuffer", JAVA_INT, JAVA_INT);
            glBlitFramebuffer = xv(getProc, "glBlitFramebuffer", JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT,
                    JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT);
            glBindRenderbuffer = xv(getProc, "glBindRenderbuffer", JAVA_INT, JAVA_INT);
            glRenderbufferStorageMultisample = xv(getProc, "glRenderbufferStorageMultisample", JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT);
            glRenderbufferStorage = xv(getProc, "glRenderbufferStorage", JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT);
            glFramebufferRenderbuffer = xv(getProc, "glFramebufferRenderbuffer", JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT);
            glCheckFramebufferStatus = x(getProc, "glCheckFramebufferStatus", JAVA_INT, JAVA_INT);

            MemorySegment one = arena.allocate(JAVA_INT), len = arena.allocate(JAVA_INT), log = arena.allocate(4096);
            int[] progs = new int[2];
            String[][] src = {{BODY_VS, BODY_FS}, {TRAIL_VS, TRAIL_FS}};
            for (int p = 0; p < 2; p++) {
                int prog = (int) createProgram.invokeExact();
                for (int k = 0; k < 2; k++) {
                    int sh = (int) createShader.invokeExact(k == 0 ? GL_VERTEX_SHADER : GL_FRAGMENT_SHADER);
                    MemorySegment ptr = arena.allocate(ADDRESS);
                    ptr.set(ADDRESS, 0, arena.allocateFrom(src[p][k]));
                    shaderSource.invokeExact(sh, 1, ptr, MemorySegment.NULL);
                    compileShader.invokeExact(sh);
                    getShaderiv.invokeExact(sh, GL_COMPILE_STATUS, one);
                    if (one.get(JAVA_INT, 0) == 0) {
                        shaderLog.invokeExact(sh, 4096, len, log);
                        throw new RuntimeException("шейдер не собран: " + log.getString(0));
                    }
                    attach.invokeExact(prog, sh);
                }
                link.invokeExact(prog);
                getProgramiv.invokeExact(prog, GL_LINK_STATUS, one);
                if (one.get(JAVA_INT, 0) == 0) {
                    programLog.invokeExact(prog, 4096, len, log);
                    throw new RuntimeException("программа не связана: " + log.getString(0));
                }
                progs[p] = prog;
            }
            bodyProg = progs[0];
            trailProg = progs[1];
            uBodyView = uniform(bodyProg, "view");
            uTrailView = uniform(trailProg, "view");
            uCam = uniform(trailProg, "cam");
            uLens = uniform(trailProg, "lens");

            MemorySegment ids = arena.allocate(JAVA_INT, 3);
            genArrays.invokeExact(2, ids);
            bodyVao = ids.getAtIndex(JAVA_INT, 0);
            trailVao = ids.getAtIndex(JAVA_INT, 1);
            genBuffers.invokeExact(3, ids);
            int quad = ids.getAtIndex(JAVA_INT, 0);
            bodyBuf = ids.getAtIndex(JAVA_INT, 1);
            trailBuf = ids.getAtIndex(JAVA_INT, 2);
            // тела: квадрат (−1…1) на вершину, 16 float на экземпляр (12 + deform)
            glBindVertexArray.invokeExact(bodyVao);
            glBindBuffer.invokeExact(GL_ARRAY_BUFFER, quad);
            MemorySegment q = arena.allocateFrom(JAVA_FLOAT, -1, -1, 1, -1, -1, 1, 1, 1);
            glBufferData.invokeExact(GL_ARRAY_BUFFER, q.byteSize(), q, GL_STATIC_DRAW);
            enableAttr.invokeExact(0);
            attrPtr.invokeExact(0, 2, GL_FLOAT, (byte) 0, 8, 0L);
            glBindBuffer.invokeExact(GL_ARRAY_BUFFER, bodyBuf);
            for (int a = 1; a <= 4; a++) {
                enableAttr.invokeExact(a);
                attrPtr.invokeExact(a, 4, GL_FLOAT, (byte) 0, 64, 16L * (a - 1));
                divisor.invokeExact(a, 1);
            }
            // следы: x, y, z, яркость (float) и цвет (4 байта) — 20 байт на точку
            glBindVertexArray.invokeExact(trailVao);
            glBindBuffer.invokeExact(GL_ARRAY_BUFFER, trailBuf);
            enableAttr.invokeExact(0);
            attrPtr.invokeExact(0, 3, GL_FLOAT, (byte) 0, 20, 0L);
            enableAttr.invokeExact(1);
            attrPtr.invokeExact(1, 1, GL_FLOAT, (byte) 0, 20, 12L);
            enableAttr.invokeExact(2);
            attrPtr.invokeExact(2, 4, GL_UNSIGNED_BYTE, (byte) 1, 20, 16L);
            glBindVertexArray.invokeExact(0);

            getInteger.invokeExact(GL_MAX_SAMPLES, one);
            samples = Math.min(4, Math.max(1, one.get(JAVA_INT, 0)));
            genFbo.invokeExact(2, ids);
            msFbo = ids.getAtIndex(JAVA_INT, 0);
            fbo = ids.getAtIndex(JAVA_INT, 1);
            genRb.invokeExact(2, ids);
            msRb = ids.getAtIndex(JAVA_INT, 0);
            rb = ids.getAtIndex(JAVA_INT, 1);
            check("подготовка");
        } finally {
            current(false);
        }
    }

    String renderer() {
        return renderer.split("/")[0] + ", MSAA ×" + samples; // «NVIDIA …/PCIe/SSE2» — без хвоста шины
    }

    // ---------- кадр ----------

    private int nBodies, nTrailPts, nTrails;

    /** Место под тела: 16 float на тело (12 + deform). */
    void beginBodies(int n) {
        inst = grow(inst, 64L * n);
        nBodies = 0;
    }

    /**
     * Тело в экранных координатах: центр, радиус ядра rr и свечения gr; свечение rgb с непрозрачностью ga;
     * ядро rgb, shade — рисовать ли объём (блик и тень). Порядок вызовов — порядок наложения.
     * Круг (без деформации).
     */
    void body(double x, double y, double rr, double gr, int glowRgb, double ga, int coreRgb, boolean shade) {
        body(x, y, rr, gr, glowRgb, ga, coreRgb, shade, 1, 0, 1, 1);
    }

    /**
     * Как {@link #body(double, double, double, double, int, double, int, boolean)} плюс экранный эллипс:
     * ось (cos, sin); {@code flat}&lt;1 вдоль неё, {@code bulge}&gt;1 по экватору (oblate).
     */
    void body(double x, double y, double rr, double gr, int glowRgb, double ga, int coreRgb, boolean shade,
              double cos, double sin, double flat, double bulge) {
        long o = 64L * nBodies++;
        MemorySegment s = inst;
        s.set(JAVA_FLOAT, o, (float) x);
        s.set(JAVA_FLOAT, o + 4, (float) y);
        s.set(JAVA_FLOAT, o + 8, (float) rr);
        s.set(JAVA_FLOAT, o + 12, (float) gr);
        s.set(JAVA_FLOAT, o + 16, ((glowRgb >> 16) & 255) / 255f);
        s.set(JAVA_FLOAT, o + 20, ((glowRgb >> 8) & 255) / 255f);
        s.set(JAVA_FLOAT, o + 24, (glowRgb & 255) / 255f);
        s.set(JAVA_FLOAT, o + 28, (float) ga);
        s.set(JAVA_FLOAT, o + 32, ((coreRgb >> 16) & 255) / 255f);
        s.set(JAVA_FLOAT, o + 36, ((coreRgb >> 8) & 255) / 255f);
        s.set(JAVA_FLOAT, o + 40, (coreRgb & 255) / 255f);
        s.set(JAVA_FLOAT, o + 44, shade ? 1f : 0f);
        s.set(JAVA_FLOAT, o + 48, (float) cos);
        s.set(JAVA_FLOAT, o + 52, (float) sin);
        s.set(JAVA_FLOAT, o + 56, (float) flat);
        s.set(JAVA_FLOAT, o + 60, (float) bulge);
    }

    /** Место под следы: всего точек и следов. */
    void beginTrails(int points, int trails) {
        verts = grow(verts, 20L * points);
        firsts = grow(firsts, 4L * Math.max(1, trails));
        counts = grow(counts, 4L * Math.max(1, trails));
        nTrailPts = 0;
        nTrails = 0;
    }

    /** Начать след: дальше {@link #point} по порядку от старых к новым. */
    void trail(int count) {
        firsts.setAtIndex(JAVA_INT, nTrails, nTrailPts);
        counts.setAtIndex(JAVA_INT, nTrails, count);
        nTrails++;
    }

    /** Точка следа относительно цели камеры; яркость 0…1 (без дымки); цвет RGB. */
    void point(double x, double y, double z, double bright, int rgb) {
        long o = 20L * nTrailPts++;
        MemorySegment s = verts;
        s.set(JAVA_FLOAT, o, (float) x);
        s.set(JAVA_FLOAT, o + 4, (float) y);
        s.set(JAVA_FLOAT, o + 8, (float) z);
        s.set(JAVA_FLOAT, o + 12, (float) bright);
        // байты R, G, B, A в памяти — как GL_UNSIGNED_BYTE ×4
        s.set(JAVA_INT, o + 16, 0xFF000000 | (rgb & 0xFF) << 16 | (rgb & 0xFF00) | (rgb >> 16) & 0xFF);
    }

    /**
     * Нарисовать следы (под телами), затем тела и прочитать картинку в px (W·H, premultiplied ARGB).
     * cam — cos/sin yaw и pitch; focal, camR; fog0, fog2L — дымка, как ScenePanel.fog.
     */
    void render(int W, int H, int[] px, double cy, double sy, double cp, double sp, double focal, double camR,
                double fog0, double fog2L, float lineWidth) throws Throwable {
        current(true);
        try {
            size(W, H);
            glBindFramebuffer.invokeExact(GL_FRAMEBUFFER, msFbo);
            glViewport.invokeExact(0, 0, W, H);
            glClearColor.invokeExact(0f, 0f, 0f, 0f);
            glClear.invokeExact(GL_COLOR_BUFFER_BIT);
            glEnable.invokeExact(GL_BLEND);
            glEnable.invokeExact(GL_MULTISAMPLE);
            glBlendFunc.invokeExact(GL_ONE, GL_ONE_MINUS_SRC_ALPHA); // premultiplied «поверх»
            if (nTrails > 0) {
                glUseProgram.invokeExact(trailProg);
                glUniform2f.invokeExact(uTrailView, (float) W, (float) H);
                glUniform4f.invokeExact(uCam, (float) cy, (float) sy, (float) cp, (float) sp);
                glUniform4f.invokeExact(uLens, (float) focal, (float) camR, (float) fog0, (float) fog2L);
                glBindVertexArray.invokeExact(trailVao);
                glBindBuffer.invokeExact(GL_ARRAY_BUFFER, trailBuf);
                glBufferData.invokeExact(GL_ARRAY_BUFFER, 20L * nTrailPts, verts, GL_STREAM_DRAW);
                glLineWidth.invokeExact(lineWidth);
                glMultiDrawArrays.invokeExact(GL_LINE_STRIP, firsts, counts, nTrails);
            }
            if (nBodies > 0) {
                glUseProgram.invokeExact(bodyProg);
                glUniform2f.invokeExact(uBodyView, (float) W, (float) H);
                glBindVertexArray.invokeExact(bodyVao);
                glBindBuffer.invokeExact(GL_ARRAY_BUFFER, bodyBuf);
                glBufferData.invokeExact(GL_ARRAY_BUFFER, 64L * nBodies, inst, GL_STREAM_DRAW);
                glDrawArraysInstanced.invokeExact(GL_TRIANGLE_STRIP, 0, 4, nBodies);
            }
            glBindVertexArray.invokeExact(0);
            // MSAA → обычный буфер → память
            glBindFramebuffer.invokeExact(GL_READ_FRAMEBUFFER, msFbo);
            glBindFramebuffer.invokeExact(GL_DRAW_FRAMEBUFFER, fbo);
            glBlitFramebuffer.invokeExact(0, 0, W, H, 0, 0, W, H, GL_COLOR_BUFFER_BIT, GL_NEAREST);
            glBindFramebuffer.invokeExact(GL_READ_FRAMEBUFFER, fbo);
            glPixelStorei.invokeExact(GL_PACK_ALIGNMENT, 4);
            glReadPixels.invokeExact(0, 0, W, H, GL_BGRA, GL_UNSIGNED_INT_8_8_8_8_REV, pixels);
            check("кадр");
            MemorySegment.copy(pixels, JAVA_INT, 0, px, 0, W * H);
        } finally {
            current(false);
        }
    }

    private void size(int W, int H) throws Throwable {
        if (W == fw && H == fh) return;
        glBindRenderbuffer.invokeExact(GL_RENDERBUFFER, msRb);
        glRenderbufferStorageMultisample.invokeExact(GL_RENDERBUFFER, samples, GL_RGBA8, W, H);
        glBindFramebuffer.invokeExact(GL_FRAMEBUFFER, msFbo);
        glFramebufferRenderbuffer.invokeExact(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_RENDERBUFFER, msRb);
        if ((int) glCheckFramebufferStatus.invokeExact(GL_FRAMEBUFFER) != GL_FRAMEBUFFER_COMPLETE) throw new RuntimeException("буфер MSAA не готов");
        glBindRenderbuffer.invokeExact(GL_RENDERBUFFER, rb);
        glRenderbufferStorage.invokeExact(GL_RENDERBUFFER, GL_RGBA8, W, H);
        glBindFramebuffer.invokeExact(GL_FRAMEBUFFER, fbo);
        glFramebufferRenderbuffer.invokeExact(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_RENDERBUFFER, rb);
        if ((int) glCheckFramebufferStatus.invokeExact(GL_FRAMEBUFFER) != GL_FRAMEBUFFER_COMPLETE) throw new RuntimeException("буфер не готов");
        long required = 4L * W * H;
        if (pixels == null || pixels.byteSize() < required) {
            Arena nextArena = Arena.ofShared();
            MemorySegment next;
            try {
                next = nextArena.allocate(Math.max(required, pixels == null ? required : pixels.byteSize() * 2), 64);
            } catch (Throwable e) { nextArena.close(); throw e; }
            releasePixels();
            pixelArena = nextArena;
            pixels = next;
        }
        fw = W;
        fh = H;
    }

    // ---------- служебное ----------

    private void releasePixels() {
        if (pixelArena != null) pixelArena.close();
        pixelArena = null;
        pixels = null;
    }

    private void current(boolean on) throws Throwable {
        int r = on ? (int) wglMakeCurrent.invokeExact(hdc, ctx) : (int) wglMakeCurrent.invokeExact(MemorySegment.NULL, MemorySegment.NULL);
        if (r == 0 && on) throw new RuntimeException("wglMakeCurrent не удался");
    }

    private void check(String where) throws Throwable {
        int e = (int) glGetError.invokeExact();
        if (e != 0) throw new RuntimeException(where + ": glGetError 0x" + Integer.toHexString(e));
    }

    private int uniform(int prog, String name) throws Throwable {
        return (int) glGetUniformLocation.invokeExact(prog, arena.allocateFrom(name));
    }

    /** Буфер не меньше need байт; старый остаётся в арене (кадры растут редко). */
    private MemorySegment grow(MemorySegment s, long need) {
        if (s != null && s.byteSize() >= need) return s;
        return arena.allocate(Math.max(need, s == null ? 4096 : s.byteSize() * 2), 64);
    }

    private MemorySegment wide(String s) {
        byte[] b = (s + "\0").getBytes(StandardCharsets.UTF_16LE);
        MemorySegment m = arena.allocate(b.length);
        MemorySegment.copy(b, 0, m, JAVA_BYTE, 0, b.length);
        return m;
    }

    private static String cstr(MemorySegment p) {
        return p.address() == 0 ? "?" : p.reinterpret(1024).getString(0);
    }

    private static MethodHandle h(SymbolLookup lib, String fn, MemoryLayout ret, MemoryLayout... args) {
        return L.downcallHandle(lib.find(fn).orElseThrow(() -> new RuntimeException("нет функции " + fn)), FunctionDescriptor.of(ret, args));
    }

    private static MethodHandle v(SymbolLookup lib, String fn, MemoryLayout... args) {
        return L.downcallHandle(lib.find(fn).orElseThrow(() -> new RuntimeException("нет функции " + fn)), FunctionDescriptor.ofVoid(args));
    }

    /** Функция OpenGL новее 1.1 — через wglGetProcAddress (нужен текущий контекст). */
    private MemorySegment proc(MethodHandle getProc, String fn) throws Throwable {
        MemorySegment p = (MemorySegment) getProc.invokeExact(arena.allocateFrom(fn));
        long a = p.address();
        if (a == 0 || a == 1 || a == 2 || a == 3 || a == -1) throw new RuntimeException("нет функции OpenGL " + fn);
        return p;
    }

    private MethodHandle x(MethodHandle getProc, String fn, MemoryLayout ret, MemoryLayout... args) throws Throwable {
        return L.downcallHandle(proc(getProc, fn), FunctionDescriptor.of(ret, args));
    }

    private MethodHandle xv(MethodHandle getProc, String fn, MemoryLayout... args) throws Throwable {
        return L.downcallHandle(proc(getProc, fn), FunctionDescriptor.ofVoid(args));
    }
}
