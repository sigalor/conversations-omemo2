APP_STL := c++_static
APP_ABI := all
APP_CPPFLAGS += -frtti
APP_CPPFLAGS += -fexceptions
APP_CPPFLAGS += -DANDROID
APP_OPTIM := release
APP_PLATFORM=android-8
LOCAL_ARM_MODE := thumb
APP_SUPPORT_FLEXIBLE_PAGE_SIZES := true
LOCAL_LDFLAGS += "-Wl,-z,max-page-size=16384"