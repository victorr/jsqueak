package JSqueak;

/**
 * Implementation refer to SqueakJS
 * @see <a href="https://github.com/codefrau/SqueakJS">codefrau/SqueakJS</a>
 *
 */
public class InterpreterProxy {

    // TODO Determine to use either a singleton or pure util class
    // Currently, some of the VM state field is accessing & modifying by SqueakVM.INSTANCE

    private InterpreterProxy() {}

    public static <T> T fetchPointerOfObject(int index, Object obj) {
        if (obj instanceof SqueakObject) {
            SqueakObject target = (SqueakObject) obj;
            /*if (target.pointers == null || target.pointers.length == 0) {
                return null;
            }
            if (target.pointers.length <= index) {
                return null;
            }*/

            Object tmp = target.pointers[index];

            return (T) tmp;
        }
        return null;
    }

    public static int fetchIntegerOfObject(int index, Object obj) {
        if (obj instanceof SqueakObject) {
            SqueakObject target = (SqueakObject) obj;
            Object tmp = target.pointers[index];
            if (tmp instanceof Integer) {
                return (int) tmp;
            }
        }
        SqueakVM.INSTANCE.setSuccess(false);
        return 0;
    }

    //region testing method

    public static boolean isBytes(Object obj) {
        if (obj instanceof SqueakObject) {
            SqueakObject target = (SqueakObject) obj;
            return target.format >= 8 && target.format <= 11;
        }
        return false;
    }

    public static boolean isPointers(Object obj) {
        if (obj instanceof SqueakObject) {
            SqueakObject target = (SqueakObject) obj;
            return target.format <= 4;
        }
        return false;
    }

    public static boolean isWords(Object obj) {
        if (obj instanceof SqueakObject) {
            SqueakObject target = (SqueakObject) obj;
            return target.format == 6;
        }
        return false;
    }

    public static boolean isWordsOrBytes(Object obj) {
        if (obj instanceof SqueakObject) {
            SqueakObject target = (SqueakObject) obj;
            return target.format == 6 || (target.format >= 8 && target.format <= 11);
        }
        return false;
    }

    public static boolean isWeak(Object obj) {
        if (obj instanceof SqueakObject) {
            SqueakObject target = (SqueakObject) obj;
            return target.format == 4;
        }
        return false;
    }

    public static boolean isMethod(Object obj) {
        if (obj instanceof SqueakObject) {
            SqueakObject target = (SqueakObject) obj;
            return target.format >= 12;
        }
        return false;
    }

    public static int SIZEOF(Object obj) {
        if (isPointers(obj)) {
            return ((SqueakObject) obj).pointersSize();
        }
        if (isWordsOrBytes(obj)) {
            return ((SqueakObject) obj).bitsSize();
        }
        return 0;
    }

    public static void primitiveFail() {
        SqueakVM.INSTANCE.setSuccess(false);
    }

    public static int SHL(int a, int b) {
        return b > 31 ? 0 : a << b;
    }
    public static int SHR(int a, int b) {
        return b > 31 ? 0 : a >>> b;
    }

    //endregion
}
