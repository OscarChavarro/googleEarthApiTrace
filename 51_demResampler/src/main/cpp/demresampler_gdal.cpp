#include <jni.h>

#include <algorithm>
#include <cmath>
#include <cstdint>
#include <mutex>
#include <string>

#include <gdal.h>
#include <gdal_priv.h>
#include <ogr_spatialref.h>

namespace {

std::once_flag gdalInitialization;

void initializeGdal() {
    std::call_once(gdalInitialization, []() {
        GDALAllRegister();
    });
}

void throwJava(JNIEnv* env, const char* className, const std::string& message) {
    jclass exceptionClass = env->FindClass(className);
    if (exceptionClass != nullptr) {
        env->ThrowNew(exceptionClass, message.c_str());
    }
}

GDALDataset* datasetFromHandle(JNIEnv* env, jlong handle) {
    if (handle == 0) {
        throwJava(env, "java/lang/IllegalStateException", "GDAL dataset is already closed");
        return nullptr;
    }
    return reinterpret_cast<GDALDataset*>(static_cast<intptr_t>(handle));
}

std::string fromJavaString(JNIEnv* env, jstring value) {
    if (value == nullptr) {
        return {};
    }
    const char* utf = env->GetStringUTFChars(value, nullptr);
    if (utf == nullptr) {
        return {};
    }
    std::string result(utf);
    env->ReleaseStringUTFChars(value, utf);
    return result;
}

}  // namespace

extern "C" {

JNIEXPORT jlong JNICALL
Java_demresampler_gdal_GdalDataset_nativeOpen(JNIEnv* env, jclass, jstring pathValue) {
    initializeGdal();
    const std::string path = fromJavaString(env, pathValue);
    if (env->ExceptionCheck()) {
        return 0;
    }

    auto* dataset = static_cast<GDALDataset*>(
        GDALOpenEx(path.c_str(), GDAL_OF_RASTER | GDAL_OF_READONLY, nullptr, nullptr, nullptr));
    if (dataset == nullptr) {
        throwJava(env, "java/io/IOException", "GDAL cannot open raster: " + path);
        return 0;
    }

    if (dataset->GetRasterCount() < 1) {
        GDALClose(dataset);
        throwJava(env, "java/io/IOException", "Raster has no bands: " + path);
        return 0;
    }

    return static_cast<jlong>(reinterpret_cast<intptr_t>(dataset));
}

JNIEXPORT void JNICALL
Java_demresampler_gdal_GdalDataset_nativeClose(JNIEnv* env, jclass, jlong handle) {
    auto* dataset = datasetFromHandle(env, handle);
    if (dataset != nullptr) {
        GDALClose(dataset);
    }
}

JNIEXPORT jdoubleArray JNICALL
Java_demresampler_gdal_GdalDataset_nativeDescribe(JNIEnv* env, jclass, jlong handle) {
    auto* dataset = datasetFromHandle(env, handle);
    if (dataset == nullptr) {
        return nullptr;
    }

    double transform[6]{};
    if (dataset->GetGeoTransform(transform) != CE_None) {
        throwJava(env, "java/io/IOException", "GDAL raster has no affine geotransform");
        return nullptr;
    }

    int hasNoData = 0;
    const double noData = dataset->GetRasterBand(1)->GetNoDataValue(&hasNoData);
    double values[11]{
        static_cast<double>(dataset->GetRasterXSize()),
        static_cast<double>(dataset->GetRasterYSize()),
        transform[0],
        transform[1],
        transform[2],
        transform[3],
        transform[4],
        transform[5],
        noData,
        static_cast<double>(hasNoData),
        static_cast<double>(dataset->GetRasterBand(1)->GetRasterDataType())
    };

    jdoubleArray result = env->NewDoubleArray(11);
    if (result != nullptr) {
        env->SetDoubleArrayRegion(result, 0, 11, values);
    }
    return result;
}

JNIEXPORT jboolean JNICALL
Java_demresampler_gdal_GdalDataset_nativeIsWgs84Geographic(JNIEnv* env, jclass, jlong handle) {
    auto* dataset = datasetFromHandle(env, handle);
    if (dataset == nullptr) {
        return JNI_FALSE;
    }

    const char* projection = dataset->GetProjectionRef();
    if (projection == nullptr || projection[0] == '\0') {
        return JNI_FALSE;
    }

    OGRSpatialReference spatialReference;
    if (spatialReference.importFromWkt(projection) != OGRERR_NONE) {
        return JNI_FALSE;
    }
    spatialReference.SetAxisMappingStrategy(OAMS_TRADITIONAL_GIS_ORDER);

    OGRSpatialReference wgs84;
    wgs84.SetWellKnownGeogCS("WGS84");
    wgs84.SetAxisMappingStrategy(OAMS_TRADITIONAL_GIS_ORDER);
    return spatialReference.IsSameGeogCS(&wgs84) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jfloatArray JNICALL
Java_demresampler_gdal_GdalDataset_nativeRead(JNIEnv* env, jclass, jlong handle,
                                               jdouble west, jdouble north,
                                               jdouble east, jdouble south,
                                               jint width, jint height) {
    auto* dataset = datasetFromHandle(env, handle);
    if (dataset == nullptr) {
        return nullptr;
    }
    if (width <= 0 || height <= 0 || !(west < east) || !(south < north)) {
        throwJava(env, "java/lang/IllegalArgumentException", "Invalid GDAL read window");
        return nullptr;
    }

    double transform[6]{};
    if (dataset->GetGeoTransform(transform) != CE_None ||
        transform[2] != 0.0 || transform[4] != 0.0 ||
        transform[1] <= 0.0 || transform[5] >= 0.0) {
        throwJava(env, "java/io/IOException",
                  "Only north-up rasters with positive X and negative Y pixel size are supported");
        return nullptr;
    }

    const double xOffset = (west - transform[0]) / transform[1];
    const double yOffset = (north - transform[3]) / transform[5];
    const double xSize = (east - west) / transform[1];
    const double ySize = (south - north) / transform[5];

    const jsize sampleCount = static_cast<jsize>(width * height);
    jfloatArray result = env->NewFloatArray(sampleCount);
    if (result == nullptr) {
        return nullptr;
    }

    jfloat* samples = env->GetFloatArrayElements(result, nullptr);
    if (samples == nullptr) {
        return nullptr;
    }

    GDALRasterIOExtraArg arguments;
    INIT_RASTERIO_EXTRA_ARG(arguments);
    arguments.eResampleAlg = GRIORA_Bilinear;
    arguments.bFloatingPointWindowValidity = TRUE;
    arguments.dfXOff = xOffset;
    arguments.dfYOff = yOffset;
    arguments.dfXSize = xSize;
    arguments.dfYSize = ySize;

    // The FABDEM VRT extends half a source pixel beyond the centre of the
    // outermost quadtree samples.  Keep the floating-point window (it defines
    // the output sample positions), but clip the integer backing window that
    // GDAL validates against the raster dimensions.
    const int xReadOffset = std::max(0, static_cast<int>(std::floor(xOffset)));
    const int yReadOffset = std::max(0, static_cast<int>(std::floor(yOffset)));
    const int xReadEnd = std::min(
        dataset->GetRasterXSize(),
        static_cast<int>(std::ceil(xOffset + xSize)));
    const int yReadEnd = std::min(
        dataset->GetRasterYSize(),
        static_cast<int>(std::ceil(yOffset + ySize)));
    if (xReadEnd <= xReadOffset || yReadEnd <= yReadOffset) {
        env->ReleaseFloatArrayElements(result, samples, JNI_ABORT);
        throwJava(env, "java/io/IOException", "GDAL read window does not intersect the raster");
        return nullptr;
    }

    const CPLErr status = GDALRasterIOEx(
        GDALGetRasterBand(dataset, 1),
        GF_Read,
        xReadOffset,
        yReadOffset,
        xReadEnd - xReadOffset,
        yReadEnd - yReadOffset,
        samples,
        width,
        height,
        GDT_Float32,
        0,
        0,
        &arguments);

    env->ReleaseFloatArrayElements(result, samples, 0);
    if (status != CE_None) {
        throwJava(env, "java/io/IOException", "GDAL failed while resampling a tile");
        return nullptr;
    }
    return result;
}

}  // extern "C"
