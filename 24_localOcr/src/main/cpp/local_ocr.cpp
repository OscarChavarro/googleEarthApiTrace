#include <Python.h>
#include <jni.h>

#include <cstdlib>
#include <cstring>
#include <dlfcn.h>
#include <mutex>
#include <string>

namespace {
std::mutex g_mutex;
bool g_pythonReady = false;
bool g_ocrReady = false;
PyObject *g_recognizeFunction = nullptr;
std::string g_language = "en";
void *g_globalPythonHandle = nullptr;

char *copyToCString(const std::string &value) {
    char *out = static_cast<char *>(std::malloc(value.size() + 1));
    if (out == nullptr) {
        return nullptr;
    }
    std::memcpy(out, value.c_str(), value.size() + 1);
    return out;
}

void clearPythonError() {
    if (PyErr_Occurred()) {
        PyErr_Clear();
    }
}

bool ensurePython() {
    if (g_pythonReady) {
        return true;
    }
    if (g_globalPythonHandle == nullptr) {
        g_globalPythonHandle = dlopen("libpython3.10.so.1.0", RTLD_NOW | RTLD_GLOBAL);
        if (g_globalPythonHandle == nullptr) {
            g_globalPythonHandle = dlopen("libpython3.10.so", RTLD_NOW | RTLD_GLOBAL);
        }
    }
    if (Py_IsInitialized()) {
        g_pythonReady = true;
        return true;
    }
    Py_Initialize();
    if (!Py_IsInitialized()) {
        return false;
    }
    PyEval_SaveThread();
    g_pythonReady = true;
    return true;
}

bool ensureOcrFunction() {
    if (g_ocrReady) {
        return g_recognizeFunction != nullptr;
    }
    g_ocrReady = true;
    if (!ensurePython()) {
        return false;
    }
    PyObject *mainModule = PyImport_AddModule("__main__");
    if (mainModule == nullptr) {
        clearPythonError();
        return false;
    }
    PyObject *globals = PyModule_GetDict(mainModule);
    const char *siteScript = R"PY(
import site
import sys

for _path in [site.getusersitepackages(), *site.getsitepackages()]:
    if _path and _path not in sys.path:
        sys.path.append(_path)
)PY";
    if (PyRun_String(siteScript, Py_file_input, globals, globals) == nullptr) {
        clearPythonError();
    }
    PyObject *osModule = PyImport_ImportModule("os");
    if (osModule != nullptr) {
        PyObject *environ = PyObject_GetAttrString(osModule, "environ");
        PyObject *language = PyUnicode_FromString(g_language.c_str());
        if (environ != nullptr && language != nullptr) {
            PyMapping_SetItemString(environ, "LOCAL_OCR_LANG", language);
        }
        Py_XDECREF(language);
        Py_XDECREF(environ);
        Py_DECREF(osModule);
    }
    clearPythonError();

    const char *script = R"PY(
import os
import re

_engine = None

def _build_engine():
    from paddleocr import PaddleOCR
    lang = os.environ.get("LOCAL_OCR_LANG", "en")
    attempts = [
        {"use_angle_cls": True, "lang": lang, "show_log": False},
        {"use_angle_cls": True, "lang": lang},
        {"lang": lang},
        {},
    ]
    last_error = None
    for kwargs in attempts:
        try:
            return PaddleOCR(**kwargs)
        except Exception as exc:
            last_error = exc
    if last_error is not None:
        raise last_error
    return PaddleOCR()

def _engine_instance():
    global _engine
    if _engine is None:
        _engine = _build_engine()
    return _engine

def _collect_strings(value, out):
    if value is None:
        return
    if isinstance(value, str):
        text = " ".join(value.split())
        if text:
            out.append(text)
        return
    if isinstance(value, dict):
        for key in ("rec_texts", "texts", "text"):
            if key in value:
                _collect_strings(value[key], out)
        return
    if isinstance(value, (list, tuple)):
        for item in value:
            _collect_strings(item, out)

def _normalize_hud_text(text):
    text = " ".join(text.split())
    longitude = re.fullmatch(r"(?:l?on|1on)\s+([+-]?\d+(?:\.\d+)?°?)", text, re.IGNORECASE)
    if longitude:
        return "lon " + longitude.group(1)
    latitude = re.fullmatch(r"(?:l?at|1at)\s+([+-]?\d+(?:\.\d+)?°?)", text, re.IGNORECASE)
    if latitude:
        return "lat " + latitude.group(1)
    return text

def recognize_png(filename):
    try:
        engine = _engine_instance()
        try:
            result = engine.ocr(filename, cls=True)
        except TypeError:
            try:
                result = engine.ocr(filename)
            except AttributeError:
                result = engine.predict(input=filename)
        texts = []
        _collect_strings(result, texts)
        deduped = []
        seen = set()
        for text in texts:
            text = _normalize_hud_text(text)
            if text not in seen:
                deduped.append(text)
                seen.add(text)
        return "\n".join(deduped)
    except Exception:
        if os.environ.get("LOCAL_OCR_DEBUG") == "1":
            import traceback
            traceback.print_exc()
        return ""
)PY";

    if (PyRun_String(script, Py_file_input, globals, globals) == nullptr) {
        clearPythonError();
        return false;
    }
    PyObject *function = PyDict_GetItemString(globals, "recognize_png");
    if (function == nullptr || !PyCallable_Check(function)) {
        clearPythonError();
        return false;
    }
    Py_INCREF(function);
    g_recognizeFunction = function;
    return true;
}

std::string recognizeLocked(const char *pngFilename) {
    if (pngFilename == nullptr || pngFilename[0] == '\0') {
        return "";
    }
    if (!ensurePython()) {
        return "";
    }
    PyGILState_STATE gil = PyGILState_Ensure();
    if (!ensureOcrFunction()) {
        PyGILState_Release(gil);
        return "";
    }
    PyObject *args = Py_BuildValue("(s)", pngFilename);
    if (args == nullptr) {
        clearPythonError();
        PyGILState_Release(gil);
        return "";
    }
    PyObject *result = PyObject_CallObject(g_recognizeFunction, args);
    Py_DECREF(args);
    if (result == nullptr) {
        clearPythonError();
        PyGILState_Release(gil);
        return "";
    }
    const char *utf8 = PyUnicode_AsUTF8(result);
    std::string out = utf8 == nullptr ? "" : utf8;
    Py_DECREF(result);
    clearPythonError();
    PyGILState_Release(gil);
    return out;
}
}

extern "C" char *local_ocr_png_to_text(const char *pngFilename) {
    std::lock_guard<std::mutex> lock(g_mutex);
    return copyToCString(recognizeLocked(pngFilename));
}

extern "C" void local_ocr_free_string(char *value) {
    std::free(value);
}

extern "C" JNIEXPORT void JNICALL
Java_dumpanalyzer_ocr_LocalOcrEngine_configureNative(JNIEnv *env, jclass, jstring language) {
    if (language == nullptr) {
        return;
    }
    const char *value = env->GetStringUTFChars(language, nullptr);
    if (value == nullptr) {
        return;
    }
    std::lock_guard<std::mutex> lock(g_mutex);
    if (!g_ocrReady) {
        g_language = value;
    }
    env->ReleaseStringUTFChars(language, value);
}

extern "C" JNIEXPORT jstring JNICALL
Java_dumpanalyzer_ocr_LocalOcrEngine_recognizePngNative(JNIEnv *env, jclass, jstring filename) {
    if (filename == nullptr) {
        return env->NewStringUTF("");
    }
    const char *path = env->GetStringUTFChars(filename, nullptr);
    if (path == nullptr) {
        return env->NewStringUTF("");
    }
    std::string recognized;
    {
        std::lock_guard<std::mutex> lock(g_mutex);
        recognized = recognizeLocked(path);
    }
    env->ReleaseStringUTFChars(filename, path);
    return env->NewStringUTF(recognized.c_str());
}
