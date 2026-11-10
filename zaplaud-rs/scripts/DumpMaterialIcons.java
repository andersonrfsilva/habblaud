/* Extrai os vetores da própria dependência Compose resolvida; não reinterpreta desenhos à mão. */
import androidx.compose.material.icons.Icons;
import androidx.compose.ui.graphics.vector.ImageVector;
import androidx.compose.ui.graphics.vector.PathNode;
import androidx.compose.ui.graphics.vector.VectorGroup;
import androidx.compose.ui.graphics.vector.VectorNode;
import androidx.compose.ui.graphics.vector.VectorPath;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

public final class DumpMaterialIcons {
    private static final Map<String, String> COMMANDS = Map.ofEntries(
        Map.entry("MoveTo", "M"), Map.entry("RelativeMoveTo", "m"),
        Map.entry("LineTo", "L"), Map.entry("RelativeLineTo", "l"),
        Map.entry("HorizontalTo", "H"), Map.entry("RelativeHorizontalTo", "h"),
        Map.entry("VerticalTo", "V"), Map.entry("RelativeVerticalTo", "v"),
        Map.entry("CurveTo", "C"), Map.entry("RelativeCurveTo", "c"),
        Map.entry("ReflectiveCurveTo", "S"), Map.entry("RelativeReflectiveCurveTo", "s"),
        Map.entry("QuadTo", "Q"), Map.entry("RelativeQuadTo", "q"),
        Map.entry("ReflectiveQuadTo", "T"), Map.entry("RelativeReflectiveQuadTo", "t"),
        Map.entry("ArcTo", "A"), Map.entry("RelativeArcTo", "a"), Map.entry("Close", "Z"));

    private static String nodes(VectorGroup group) throws Exception {
        if (group.getRotation() != 0 || group.getPivotX() != 0 || group.getPivotY() != 0
                || group.getScaleX() != 1 || group.getScaleY() != 1 || group.getTranslationX() != 0
                || group.getTranslationY() != 0 || !group.getClipPathData().isEmpty()) {
            throw new IllegalArgumentException("Grupo transformado exige suporte explícito");
        }
        StringBuilder result = new StringBuilder();
        for (VectorNode node : group) {
            if (node instanceof VectorGroup nested) {
                result.append(nodes(nested));
            } else if (node instanceof VectorPath path) {
                if (path.getStroke() != null || path.getTrimPathStart() != 0
                        || path.getTrimPathEnd() != 1 || path.getTrimPathOffset() != 0) {
                    throw new IllegalArgumentException("Stroke/trim exige suporte explícito");
                }
                result.append("<path fill=\"#000000\" fill-opacity=\"").append(path.getFillAlpha())
                    .append("\" fill-rule=\"")
                    .append((int) path.getClass().getMethod("getPathFillType-Rg-k1Os").invoke(path) == 0 ? "nonzero" : "evenodd")
                    .append("\" d=\"");
                for (PathNode segment : path.getPathData()) {
                    String command = COMMANDS.get(segment.getClass().getSimpleName());
                    if (command == null) throw new IllegalArgumentException("PathNode desconhecido: " + segment);
                    result.append(command);
                    for (int index = 1; ; index++) {
                        try {
                            Object value = segment.getClass().getMethod("component" + index).invoke(segment);
                            result.append(value instanceof Boolean flag ? (flag ? "1" : "0") : value.toString()).append(' ');
                        } catch (NoSuchMethodException complete) {
                            break;
                        }
                    }
                }
                result.append("\"/>\n");
            } else {
                throw new IllegalArgumentException("VectorNode desconhecido: " + node);
            }
        }
        return result.toString();
    }

    public static void main(String[] args) throws Exception {
        if (args.length < 2) throw new IllegalArgumentException("Uso: DumpMaterialIcons <ui/widgets> <IconName>...");
        Path root = Path.of(args[0]);
        Files.createDirectories(root.resolve("material-icons"));
        StringBuilder global = new StringBuilder("// Gerado por scripts/DumpMaterialIcons.java da dependência Compose.\nexport global MaterialIcons {\n");
        for (int index = 1; index < args.length; index++) {
            String icon = args[index];
            ImageVector vector = (ImageVector) Class.forName("androidx.compose.material.icons.filled." + icon + "Kt")
                .getMethod("get" + icon, Icons.Filled.class).invoke(null, Icons.Filled.INSTANCE);
            String name = icon.replaceAll("([a-z0-9])([A-Z])", "$1-$2").toLowerCase(java.util.Locale.ROOT);
            String svg = "<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"24\" height=\"24\" viewBox=\"0 0 "
                + vector.getViewportWidth() + " " + vector.getViewportHeight() + "\">\n" + nodes(vector.getRoot()) + "</svg>\n";
            Files.writeString(root.resolve("material-icons/" + name + ".svg"), svg);
            global.append("    out property <image> ").append(name).append(": @image-url(\"material-icons/")
                .append(name).append(".svg\");\n");
        }
        Files.writeString(root.resolve("material-icons.slint"), global.append("}\n"));
    }
}
