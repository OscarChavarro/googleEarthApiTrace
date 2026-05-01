package pyramidalimagebuilder.model;

public final class RenderingConfiguration {
    private final vsdk.toolkit.common.RendererConfiguration delegate =
        new vsdk.toolkit.common.RendererConfiguration();

    public RenderingConfiguration() {
        delegate.setWires(false);
    }

    public vsdk.toolkit.common.RendererConfiguration getDelegate() {
        return delegate;
    }

    public boolean isSurfacesSet() {
        return delegate.isSurfacesSet();
    }

    public boolean isWiresSet() {
        return delegate.isWiresSet();
    }

    public boolean isPointsSet() {
        return delegate.isPointsSet();
    }

    public boolean isTextureSet() {
        return delegate.isTextureSet();
    }
}
