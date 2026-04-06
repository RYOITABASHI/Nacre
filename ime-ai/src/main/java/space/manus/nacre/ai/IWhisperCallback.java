/*
 * Auto-generated AIDL stub (hand-written for Termux aarch64 compatibility).
 * Equivalent to compiling IWhisperCallback.aidl
 */
package space.manus.nacre.ai;

public interface IWhisperCallback extends android.os.IInterface {

    void onResult(String text) throws android.os.RemoteException;
    void onPartialResult(String text, boolean isStable) throws android.os.RemoteException;
    void onError(String message) throws android.os.RemoteException;

    /** Default implementation */
    public static class Default implements IWhisperCallback {
        @Override public void onResult(String text) throws android.os.RemoteException {}
        @Override public void onPartialResult(String text, boolean isStable) throws android.os.RemoteException {}
        @Override public void onError(String message) throws android.os.RemoteException {}
        @Override public android.os.IBinder asBinder() { return null; }
    }

    /** Local-side IPC implementation stub class. */
    public static abstract class Stub extends android.os.Binder implements IWhisperCallback {

        private static final String DESCRIPTOR = "space.manus.nacre.ai.IWhisperCallback";
        static final int TRANSACTION_onResult = android.os.IBinder.FIRST_CALL_TRANSACTION + 0;
        static final int TRANSACTION_onPartialResult = android.os.IBinder.FIRST_CALL_TRANSACTION + 1;
        static final int TRANSACTION_onError = android.os.IBinder.FIRST_CALL_TRANSACTION + 2;

        public Stub() { this.attachInterface(this, DESCRIPTOR); }

        public static IWhisperCallback asInterface(android.os.IBinder obj) {
            if (obj == null) return null;
            android.os.IInterface iin = obj.queryLocalInterface(DESCRIPTOR);
            if (iin instanceof IWhisperCallback) return (IWhisperCallback) iin;
            return new Proxy(obj);
        }

        @Override public android.os.IBinder asBinder() { return this; }

        @Override
        public boolean onTransact(int code, android.os.Parcel data, android.os.Parcel reply, int flags) throws android.os.RemoteException {
            switch (code) {
                case TRANSACTION_onResult: {
                    data.enforceInterface(DESCRIPTOR);
                    String _arg0 = data.readString();
                    this.onResult(_arg0);
                    return true;
                }
                case TRANSACTION_onPartialResult: {
                    data.enforceInterface(DESCRIPTOR);
                    String _arg0 = data.readString();
                    boolean _arg1 = (data.readInt() != 0);
                    this.onPartialResult(_arg0, _arg1);
                    return true;
                }
                case TRANSACTION_onError: {
                    data.enforceInterface(DESCRIPTOR);
                    String _arg0 = data.readString();
                    this.onError(_arg0);
                    return true;
                }
            }
            return super.onTransact(code, data, reply, flags);
        }

        private static class Proxy implements IWhisperCallback {
            private final android.os.IBinder mRemote;
            Proxy(android.os.IBinder remote) { mRemote = remote; }
            @Override public android.os.IBinder asBinder() { return mRemote; }

            @Override
            public void onResult(String text) throws android.os.RemoteException {
                android.os.Parcel _data = android.os.Parcel.obtain();
                try {
                    _data.writeInterfaceToken(DESCRIPTOR);
                    _data.writeString(text);
                    mRemote.transact(TRANSACTION_onResult, _data, null, android.os.IBinder.FLAG_ONEWAY);
                } finally { _data.recycle(); }
            }

            @Override
            public void onPartialResult(String text, boolean isStable) throws android.os.RemoteException {
                android.os.Parcel _data = android.os.Parcel.obtain();
                try {
                    _data.writeInterfaceToken(DESCRIPTOR);
                    _data.writeString(text);
                    _data.writeInt(isStable ? 1 : 0);
                    mRemote.transact(TRANSACTION_onPartialResult, _data, null, android.os.IBinder.FLAG_ONEWAY);
                } finally { _data.recycle(); }
            }

            @Override
            public void onError(String message) throws android.os.RemoteException {
                android.os.Parcel _data = android.os.Parcel.obtain();
                try {
                    _data.writeInterfaceToken(DESCRIPTOR);
                    _data.writeString(message);
                    mRemote.transact(TRANSACTION_onError, _data, null, android.os.IBinder.FLAG_ONEWAY);
                } finally { _data.recycle(); }
            }
        }
    }
}
