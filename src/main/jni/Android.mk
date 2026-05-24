LOCAL_PATH := $(call my-dir)

include $(CLEAR_VARS)

LOCAL_MODULE    := NativeImageProcessor
LOCAL_SRC_FILES := NativeImageProcessor.cpp

include $(BUILD_SHARED_LIBRARY)

# Argon2 — built directly from the phc-winner-argon2 reference C implementation
# and the Signal/Molly JNI wrapper. Building in-tree ensures the library is
# compiled with APP_SUPPORT_FLEXIBLE_PAGE_SIZES=true (Application.mk), which
# adds -Wl,-z,max-page-size=16384 and fixes the 16 KB alignment requirement
# for Android 15+ / Play Store. The im.molly:argon2 AAR was not built with
# this flag, which is why it was replaced.
include $(CLEAR_VARS)

ARGON2_SRC := $(LOCAL_PATH)/argon2/src

LOCAL_MODULE     := argon2
LOCAL_C_INCLUDES := $(LOCAL_PATH)/argon2/include \
                    $(LOCAL_PATH)
LOCAL_CFLAGS     += -Wall
ifeq ($(APP_OPTIM),release)
LOCAL_LDLIBS     += -Wl,--build-id=none
endif

LOCAL_SRC_FILES  := org_signal_argon2_Argon2Native.c \
                    $(ARGON2_SRC)/blake2/blake2b.c \
                    $(ARGON2_SRC)/argon2.c \
                    $(ARGON2_SRC)/core.c \
                    $(ARGON2_SRC)/encoding.c \
                    $(ARGON2_SRC)/genkat.c \
                    $(ARGON2_SRC)/ref.c \
                    $(ARGON2_SRC)/thread.c

include $(BUILD_SHARED_LIBRARY)
