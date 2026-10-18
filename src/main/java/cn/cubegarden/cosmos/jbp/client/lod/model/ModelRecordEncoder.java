package cn.cubegarden.cosmos.jbp.client.lod.model;

import cn.cubegarden.cosmos.jbp.client.lod.data.BlockModelData;

import java.util.Objects;

/** Pure encoding of six GPU-rasterized face summaries into the upstream BlockModel ABI. */
public final class ModelRecordEncoder {
    public enum RenderLayer {
        SOLID,
        CUTOUT,
        TRANSLUCENT
    }

    /** Counts and depth values collected from the Vulkan raster result for one 16x16 face. */
    public record FaceStats(int minX, int maxX, int minY, int maxY,
                            int writtenPixels, int tintablePixels, int tintedPixels,
                            float averageDepth, float nearestDepth) {
        public FaceStats {
            if (writtenPixels < 0 || tintablePixels < 0 || tintedPixels < 0
                    || tintedPixels > tintablePixels || tintablePixels > writtenPixels) {
                throw new IllegalArgumentException("invalid face pixel counts");
            }
            if (writtenPixels == 0) {
                if (minX != -1 || maxX != -1 || minY != -1 || maxY != -1) {
                    throw new IllegalArgumentException("empty face bounds must be -1");
                }
            } else if (minX < 0 || maxX >= ModelAtlasLayout.FACE_SIZE || minX > maxX
                    || minY < 0 || maxY >= ModelAtlasLayout.FACE_SIZE || minY > maxY) {
                throw new IllegalArgumentException("face bounds must fit the 16x16 tile");
            }
        }

        public static FaceStats empty() {
            return new FaceStats(-1, -1, -1, -1, 0, 0, 0, -1, -1);
        }

        public boolean exists() {
            return writtenPixels != 0;
        }

        public int tintState() {
            if (tintablePixels == 0) {
                return 0;
            }
            if (tintedPixels == 0) {
                return 1;
            }
            return tintedPixels == tintablePixels ? 3 : 2;
        }

        public float depth(RenderLayer layer) {
            Objects.requireNonNull(layer, "layer");
            return layer == RenderLayer.SOLID ? averageDepth : nearestDepth;
        }
    }

    public record FaceRecord(int packedFaceData, int metadataByte, boolean occludesNeighbour) {
    }

    public record EncodedModel(BlockModelData data, long metadata) {
    }

    private ModelRecordEncoder() {
    }

    public static int modelFlags(boolean hasTintSource, boolean biomeDependent,
                                 boolean translucent, boolean shaded) {
        return (hasTintSource ? 1 : 0)
                | (biomeDependent ? 1 << 1 : 0)
                | (translucent ? 1 << 2 : 0)
                | (shaded ? 1 << 3 : 0);
    }

    public static FaceRecord encodeFace(FaceStats stats, RenderLayer layer, boolean tintSourcePresent) {
        Objects.requireNonNull(stats, "stats");
        Objects.requireNonNull(layer, "layer");
        if (!stats.exists()) {
            return new FaceRecord(-1, 0xff, false);
        }
        float depth = stats.depth(layer);
        if (!Float.isFinite(depth) || depth < 0.0f || depth > 1.0f) {
            throw new IllegalArgumentException("visible face depth must be in [0, 1]: " + depth);
        }
        boolean fullBlock = stats.minX == 0 && stats.minY == 0
                && stats.maxX == ModelAtlasLayout.FACE_SIZE - 1
                && stats.maxY == ModelAtlasLayout.FACE_SIZE - 1;
        int faceMetadata = fullBlock ? 2 : 0;
        boolean occludes = layer != RenderLayer.TRANSLUCENT && depth < 0.1;
        if (occludes) {
            occludes = ((float) stats.writtenPixels) / (ModelAtlasLayout.FACE_SIZE * ModelAtlasLayout.FACE_SIZE) > 0.9;
        }
        if (occludes) {
            faceMetadata |= 1;
        }
        if (depth < 0.3) {
            faceMetadata |= 1 << 2;
        }
        if (depth > 0.01 || layer == RenderLayer.TRANSLUCENT) {
            faceMetadata |= 1 << 3;
        }

        int faceData = stats.minX | (stats.maxX << 4) | (stats.minY << 8) | (stats.maxY << 12);
        faceData |= Math.min(Math.round(depth * 64.0f), 62) << 16;
        int area = (stats.maxX - stats.minX + 1) * (stats.maxY - stats.minY + 1);
        boolean needsAlphaDiscard = ((float) stats.writtenPixels) / area < 0.9;
        needsAlphaDiscard |= layer != RenderLayer.SOLID;
        needsAlphaDiscard &= layer != RenderLayer.TRANSLUCENT;
        if (needsAlphaDiscard) {
            faceData |= 1 << 22;
        }
        if (!fullBlock && layer != RenderLayer.TRANSLUCENT) {
            faceData |= 1 << 23;
        }
        if (tintSourcePresent) {
            int tintState = stats.tintState();
            if (tintState == 2) {
                faceData |= 1 << 24;
            } else if (tintState == 3) {
                faceData |= 2 << 24;
            }
        }
        return new FaceRecord(faceData, faceMetadata, occludes);
    }

    /** Reduces one baked face to the counts and depths the encoder needs (upstream processTextureBakeResult). */
    public static FaceStats statsFor(ColourDepthTextureData face, RenderLayer layer) {
        Objects.requireNonNull(face, "face");
        Objects.requireNonNull(layer, "layer");
        int checkMode = layer == RenderLayer.SOLID
                ? TextureUtils.WRITE_CHECK_STENCIL : TextureUtils.WRITE_CHECK_ALPHA;
        int written = TextureUtils.getWrittenPixelCount(face, checkMode);
        if (written == 0) {
            return FaceStats.empty();
        }
        int[] bounds = TextureUtils.computeBounds(face, checkMode);
        int tintable = 0;
        int tinted = 0;
        int[] colour = face.colour();
        int[] depth = face.depth();
        for (int i = 0; i < colour.length; i++) {
            if (!pixelWritten(face, checkMode, i)
                    || (colour[i] & 0xFFFFFF) == 0 || (colour[i] >>> 24) == 0) {
                continue;
            }
            tintable++;
            if ((depth[i] & (1 << 7)) != 0) {
                tinted++;
            }
        }
        float average = TextureUtils.computeDepth(face, TextureUtils.DEPTH_MODE_AVG, checkMode);
        float nearest = TextureUtils.computeDepth(face, TextureUtils.DEPTH_MODE_MIN, checkMode);
        return new FaceStats(bounds[0], bounds[1], bounds[2], bounds[3], written, tintable, tinted,
                average, nearest);
    }

    private static boolean pixelWritten(ColourDepthTextureData face, int checkMode, int index) {
        return checkMode == TextureUtils.WRITE_CHECK_STENCIL
                ? (face.depth()[index] & 0xFF) != 0
                : ((face.colour()[index] >>> 24) & 0xFF) > 1;
    }

    /** Raster flags bit 1: the bake used darkened (unshaded-tint) textures, so transparent texels are not solidified. */
    public static boolean needsDarkenedMips(int rasterFlags) {
        return (rasterFlags & 2) != 0;
    }

    /** Raster flags bit 0: shaded. */
    public static boolean isShaded(int rasterFlags) {
        return (rasterFlags & 1) != 0;
    }

    /** Upstream processModelResult layer choice: bit 2 translucent geometry present, bit 3 discard present. */
    public static RenderLayer selectLayer(int rasterFlags, ColourDepthTextureData[] faces, boolean isLeaves) {
        Objects.requireNonNull(faces, "faces");
        RenderLayer layer = null;
        if ((rasterFlags & 4) != 0) {
            boolean anyTranslucent = false;
            for (ColourDepthTextureData face : faces) {
                anyTranslucent |= TextureUtils.hasTranslucentPixel(face);
                if (anyTranslucent) {
                    break;
                }
            }
            if (anyTranslucent) {
                layer = RenderLayer.TRANSLUCENT;
            } else {
                boolean solid = true;
                for (ColourDepthTextureData face : faces) {
                    solid &= TextureUtils.isSolidWhereDrawn(face);
                    if (!solid) {
                        break;
                    }
                }
                layer = solid ? RenderLayer.SOLID : RenderLayer.CUTOUT;
            }
        }
        if (layer == null && (rasterFlags & 8) != 0) {
            layer = RenderLayer.CUTOUT;
        }
        if (isLeaves) {
            layer = RenderLayer.SOLID;
        }
        return layer == null ? RenderLayer.SOLID : layer;
    }

    public static EncodedModel encode(FaceStats[] faces, RenderLayer layer,
                                      boolean hasTintSource, boolean biomeDependent,
                                      boolean shaded, boolean hasFluidState, boolean isFluid,
                                      boolean cullsSame, int lightEmission,
                                      int colourTint, int customId) {
        Objects.requireNonNull(faces, "faces");
        Objects.requireNonNull(layer, "layer");
        if (faces.length != 6) {
            throw new IllegalArgumentException("a block model requires exactly six directional faces");
        }
        if (lightEmission < 0 || lightEmission > 15) {
            throw new IllegalArgumentException("light emission must be in [0, 15]");
        }
        FaceRecord[] encoded = new FaceRecord[6];
        int[] data = new int[6];
        long metadata = 0;
        boolean fullyOpaque = true;
        for (int face = 5; face >= 0; face--) {
            encoded[face] = encodeFace(faces[face], layer, hasTintSource);
            data[face] = encoded[face].packedFaceData;
            metadata |= (long) encoded[face].metadataByte << (face * 8);
            fullyOpaque &= encoded[face].occludesNeighbour;
        }
        boolean doubleSided = (!faces[0].exists() && !faces[1].exists())
                || (!faces[2].exists() && !faces[3].exists())
                || (!faces[4].exists() && !faces[5].exists());
        int globalFlags = (biomeDependent ? 1 : 0)
                | (layer == RenderLayer.TRANSLUCENT ? 1 << 1 : 0)
                | (doubleSided ? 1 << 2 : 0)
                | (hasFluidState ? 1 << 3 : 0)
                | (isFluid ? 1 << 4 : 0)
                | (cullsSame ? 1 << 5 : 0)
                | (fullyOpaque ? 1 << 6 : 0)
                | (lightEmission << 7);
        metadata |= (long) globalFlags << 48;
        int flags = modelFlags(hasTintSource, biomeDependent, layer == RenderLayer.TRANSLUCENT, shaded);
        return new EncodedModel(new BlockModelData(data[0], data[1], data[2], data[3], data[4], data[5],
                flags, colourTint, customId, 0, 0, 0, 0, 0, 0, 0), metadata);
    }
}
