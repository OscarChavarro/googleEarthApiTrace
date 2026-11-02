package planetdemviewer.render;

import vsdk.toolkit.common.color.ColorRgb;
import vsdk.toolkit.environment.material.RendererConfiguration;
import vsdk.toolkit.environment.material.SimpleMaterial;

/** The colors, materials and shading settings of the viewer's own decorations. */
public final class RenderStyles {
    private RenderStyles() {
    }

    public static ColorRgb backgroundColor() {
        return new ColorRgb(0.02, 0.02, 0.05);
    }

    /**
     * The values that Vitral's mesh renderer gives the Cook-Terrance shaders for a material
     * that has no microfacet data; the Fresnel reflectance there is the material's specular color.
     */
    public static final float COOK_ROUGHNESS = 0.35f;
    public static final float COOK_ALPHA = COOK_ROUGHNESS * COOK_ROUGHNESS;
    public static final float COOK_KD = 1.0f;
    public static final float COOK_KS = 1.0f;

    public static RendererConfiguration noLightConfiguration() {
        RendererConfiguration configuration = new RendererConfiguration();
        configuration.setTexture(false);
        configuration.setBumpMap(false);
        configuration.setShadingType(RendererConfiguration.SHADING_TYPE_NOLIGHT);
        return configuration;
    }

    public static SimpleMaterial wireMaterial(ColorRgb color) {
        return flatMaterial(color == null ? new ColorRgb(1.0, 1.0, 1.0) : color);
    }

    public static SimpleMaterial pointMaterial() {
        return flatMaterial(new ColorRgb(1.0, 0.1, 0.1));
    }

    /** Terrain under imagery only modulates the imagery's colors. */
    public static SimpleMaterial whiteTerrainMaterial() {
        SimpleMaterial material = new SimpleMaterial();
        material = material.withAmbient(new ColorRgb(0.18, 0.18, 0.18));
        material = material.withDiffuse(new ColorRgb(1.0, 1.0, 1.0));
        return material.withSpecular(new ColorRgb(1.0, 1.0, 1.0)).withPhongExponent(32.0);
    }

    public static RendererConfiguration markerQuality() {
        RendererConfiguration quality = new RendererConfiguration();
        quality.setSurfaces(true);
        quality.setWires(false);
        quality.setTexture(false);
        quality.setBumpMap(false);
        quality.setShadingType(RendererConfiguration.SHADING_TYPE_PHONG);
        return quality;
    }

    public static SimpleMaterial markerMaterial() {
        SimpleMaterial material = new SimpleMaterial();
        material = material.withAmbient(new ColorRgb(0.10, 0.45, 0.75));
        material = material.withDiffuse(new ColorRgb(0.30, 0.80, 1.00));
        return material.withSpecular(new ColorRgb(0.70, 0.90, 1.00)).withPhongExponent(24.0);
    }

    private static SimpleMaterial flatMaterial(ColorRgb diffuse) {
        SimpleMaterial material = new SimpleMaterial();
        material = material.withAmbient(new ColorRgb(0.0, 0.0, 0.0));
        material = material.withDiffuse(diffuse);
        return material.withSpecular(new ColorRgb(0.0, 0.0, 0.0));
    }
}
