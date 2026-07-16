package pyramidalimageexporter.model;

/**
 * Rigid placement of a child matrix in the parent's grid refined by one
 * quadtree level.  It is relative metadata and becomes absolute only after
 * the parent matrix has an accepted absolute grid anchor.
 */
public record ParentGridTransform(int rowOffset, int colOffset) {
}
