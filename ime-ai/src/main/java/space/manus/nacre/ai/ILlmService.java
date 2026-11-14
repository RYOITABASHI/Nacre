/*
 * Auto-generated AIDL stub (hand-written for Termux aarch64 compatibility).
 * Equivalent to compiling ILlmService.aidl
 */
package space.manus.nacre.ai;

public interface ILlmService extends android.os.IInterface {

    boolean isModelLoaded() throws android.os.RemoteException;
    boolean isGenerating() throws android.os.RemoteException;
    void loadModel(String modelPath) throws android.os.RemoteException;
    void unloadModel() throws android.os.RemoteException;
    void transform(String text, String instruction, ILlmCallback callback) throws android.os.RemoteException;
    void cancelGeneration() throws android.os.RemoteException;
    // zenz neural conversion (separate model handle from the cleanup LLM)
    void loadZenzModel(String modelPath) throws android.os.RemoteException;
    boolean isZenzModelLoaded() throws android.os.RemoteException;
    String getZenzLoadError() throws android.os.RemoteException;
    void zenzConvert(String katakanaReading, String leftContext, ILlmCallback callback) throws android.os.RemoteException;
    void cancelZenzGeneration() throws android.os.RemoteException;

    public static class Default implements ILlmService {
        @Override public boolean isModelLoaded() throws android.os.RemoteException { return false; }
        @Override public boolean isGenerating() throws android.os.RemoteException { return false; }
        @Override public void loadModel(String modelPath) throws android.os.RemoteException {}
        @Override public void unloadModel() throws android.os.RemoteException {}
        @Override public void transform(String text, String instruction, ILlmCallback callback) throws android.os.RemoteException {}
        @Override public void cancelGeneration() throws android.os.RemoteException {}
        @Override public void loadZenzModel(String modelPath) throws android.os.RemoteException {}
        @Override public boolean isZenzModelLoaded() throws android.os.RemoteException { return false; }
        @Override public String getZenzLoadError() throws android.os.RemoteException { return ""; }
        @Override public void zenzConvert(String katakanaReading, String leftContext, ILlmCallback callback) throws android.os.RemoteException {}
        @Override public void cancelZenzGeneration() throws android.os.RemoteException {}
        @Override public android.os.IBinder asBinder() { return null; }
    }

    public static abstract class Stub extends android.os.Binder implements ILlmService {

        private static final String DESCRIPTOR = "space.manus.nacre.ai.ILlmService";
        static final int TRANSACTION_isModelLoaded = android.os.IBinder.FIRST_CALL_TRANSACTION + 0;
        static final int TRANSACTION_isGenerating = android.os.IBinder.FIRST_CALL_TRANSACTION + 1;
        static final int TRANSACTION_loadModel = android.os.IBinder.FIRST_CALL_TRANSACTION + 2;
        static final int TRANSACTION_unloadModel = android.os.IBinder.FIRST_CALL_TRANSACTION + 3;
        static final int TRANSACTION_transform = android.os.IBinder.FIRST_CALL_TRANSACTION + 4;
        static final int TRANSACTION_cancelGeneration = android.os.IBinder.FIRST_CALL_TRANSACTION + 5;
        static final int TRANSACTION_loadZenzModel = android.os.IBinder.FIRST_CALL_TRANSACTION + 6;
        static final int TRANSACTION_isZenzModelLoaded = android.os.IBinder.FIRST_CALL_TRANSACTION + 7;
        static final int TRANSACTION_getZenzLoadError = android.os.IBinder.FIRST_CALL_TRANSACTION + 8;
        static final int TRANSACTION_zenzConvert = android.os.IBinder.FIRST_CALL_TRANSACTION + 9;
        static final int TRANSACTION_cancelZenzGeneration = android.os.IBinder.FIRST_CALL_TRANSACTION + 10;

        public Stub() { this.attachInterface(this, DESCRIPTOR); }

        public static ILlmService asInterface(android.os.IBinder obj) {
            if (obj == null) return null;
            android.os.IInterface iin = obj.queryLocalInterface(DESCRIPTOR);
            if (iin instanceof ILlmService) return (ILlmService) iin;
            return new Proxy(obj);
        }

        @Override public android.os.IBinder asBinder() { return this; }

        @Override
        public boolean onTransact(int code, android.os.Parcel data, android.os.Parcel reply, int flags) throws android.os.RemoteException {
            switch (code) {
                case TRANSACTION_isModelLoaded: {
                    data.enforceInterface(DESCRIPTOR);
                    boolean _result = this.isModelLoaded();
                    reply.writeNoException();
                    reply.writeInt(_result ? 1 : 0);
                    return true;
                }
                case TRANSACTION_isGenerating: {
                    data.enforceInterface(DESCRIPTOR);
                    boolean _result = this.isGenerating();
                    reply.writeNoException();
                    reply.writeInt(_result ? 1 : 0);
                    return true;
                }
                case TRANSACTION_loadModel: {
                    data.enforceInterface(DESCRIPTOR);
                    this.loadModel(data.readString());
                    reply.writeNoException();
                    return true;
                }
                case TRANSACTION_unloadModel: {
                    data.enforceInterface(DESCRIPTOR);
                    this.unloadModel();
                    reply.writeNoException();
                    return true;
                }
                case TRANSACTION_transform: {
                    data.enforceInterface(DESCRIPTOR);
                    String _arg0 = data.readString();
                    String _arg1 = data.readString();
                    ILlmCallback _arg2 = ILlmCallback.Stub.asInterface(data.readStrongBinder());
                    this.transform(_arg0, _arg1, _arg2);
                    reply.writeNoException();
                    return true;
                }
                case TRANSACTION_cancelGeneration: {
                    data.enforceInterface(DESCRIPTOR);
                    this.cancelGeneration();
                    reply.writeNoException();
                    return true;
                }
                case TRANSACTION_loadZenzModel: {
                    data.enforceInterface(DESCRIPTOR);
                    this.loadZenzModel(data.readString());
                    reply.writeNoException();
                    return true;
                }
                case TRANSACTION_isZenzModelLoaded: {
                    data.enforceInterface(DESCRIPTOR);
                    boolean _result = this.isZenzModelLoaded();
                    reply.writeNoException();
                    reply.writeInt(_result ? 1 : 0);
                    return true;
                }
                case TRANSACTION_getZenzLoadError: {
                    data.enforceInterface(DESCRIPTOR);
                    String _result = this.getZenzLoadError();
                    reply.writeNoException();
                    reply.writeString(_result);
                    return true;
                }
                case TRANSACTION_zenzConvert: {
                    data.enforceInterface(DESCRIPTOR);
                    String _arg0 = data.readString();
                    String _arg1 = data.readString();
                    ILlmCallback _arg2 = ILlmCallback.Stub.asInterface(data.readStrongBinder());
                    this.zenzConvert(_arg0, _arg1, _arg2);
                    reply.writeNoException();
                    return true;
                }
                case TRANSACTION_cancelZenzGeneration: {
                    data.enforceInterface(DESCRIPTOR);
                    this.cancelZenzGeneration();
                    reply.writeNoException();
                    return true;
                }
            }
            return super.onTransact(code, data, reply, flags);
        }

        private static class Proxy implements ILlmService {
            private final android.os.IBinder mRemote;
            Proxy(android.os.IBinder remote) { mRemote = remote; }
            @Override public android.os.IBinder asBinder() { return mRemote; }

            @Override
            public boolean isModelLoaded() throws android.os.RemoteException {
                android.os.Parcel _data = android.os.Parcel.obtain();
                android.os.Parcel _reply = android.os.Parcel.obtain();
                try {
                    _data.writeInterfaceToken(DESCRIPTOR);
                    mRemote.transact(TRANSACTION_isModelLoaded, _data, _reply, 0);
                    _reply.readException();
                    return _reply.readInt() != 0;
                } finally { _reply.recycle(); _data.recycle(); }
            }

            @Override
            public boolean isGenerating() throws android.os.RemoteException {
                android.os.Parcel _data = android.os.Parcel.obtain();
                android.os.Parcel _reply = android.os.Parcel.obtain();
                try {
                    _data.writeInterfaceToken(DESCRIPTOR);
                    mRemote.transact(TRANSACTION_isGenerating, _data, _reply, 0);
                    _reply.readException();
                    return _reply.readInt() != 0;
                } finally { _reply.recycle(); _data.recycle(); }
            }

            @Override
            public void loadModel(String modelPath) throws android.os.RemoteException {
                android.os.Parcel _data = android.os.Parcel.obtain();
                android.os.Parcel _reply = android.os.Parcel.obtain();
                try {
                    _data.writeInterfaceToken(DESCRIPTOR);
                    _data.writeString(modelPath);
                    mRemote.transact(TRANSACTION_loadModel, _data, _reply, 0);
                    _reply.readException();
                } finally { _reply.recycle(); _data.recycle(); }
            }

            @Override
            public void unloadModel() throws android.os.RemoteException {
                android.os.Parcel _data = android.os.Parcel.obtain();
                android.os.Parcel _reply = android.os.Parcel.obtain();
                try {
                    _data.writeInterfaceToken(DESCRIPTOR);
                    mRemote.transact(TRANSACTION_unloadModel, _data, _reply, 0);
                    _reply.readException();
                } finally { _reply.recycle(); _data.recycle(); }
            }

            @Override
            public void transform(String text, String instruction, ILlmCallback callback) throws android.os.RemoteException {
                android.os.Parcel _data = android.os.Parcel.obtain();
                android.os.Parcel _reply = android.os.Parcel.obtain();
                try {
                    _data.writeInterfaceToken(DESCRIPTOR);
                    _data.writeString(text);
                    _data.writeString(instruction);
                    _data.writeStrongBinder(callback != null ? callback.asBinder() : null);
                    mRemote.transact(TRANSACTION_transform, _data, _reply, 0);
                    _reply.readException();
                } finally { _reply.recycle(); _data.recycle(); }
            }

            @Override
            public void cancelGeneration() throws android.os.RemoteException {
                android.os.Parcel _data = android.os.Parcel.obtain();
                android.os.Parcel _reply = android.os.Parcel.obtain();
                try {
                    _data.writeInterfaceToken(DESCRIPTOR);
                    mRemote.transact(TRANSACTION_cancelGeneration, _data, _reply, 0);
                    _reply.readException();
                } finally { _reply.recycle(); _data.recycle(); }
            }

            @Override
            public void loadZenzModel(String modelPath) throws android.os.RemoteException {
                android.os.Parcel _data = android.os.Parcel.obtain();
                android.os.Parcel _reply = android.os.Parcel.obtain();
                try {
                    _data.writeInterfaceToken(DESCRIPTOR);
                    _data.writeString(modelPath);
                    mRemote.transact(TRANSACTION_loadZenzModel, _data, _reply, 0);
                    _reply.readException();
                } finally { _reply.recycle(); _data.recycle(); }
            }

            @Override
            public boolean isZenzModelLoaded() throws android.os.RemoteException {
                android.os.Parcel _data = android.os.Parcel.obtain();
                android.os.Parcel _reply = android.os.Parcel.obtain();
                try {
                    _data.writeInterfaceToken(DESCRIPTOR);
                    mRemote.transact(TRANSACTION_isZenzModelLoaded, _data, _reply, 0);
                    _reply.readException();
                    return _reply.readInt() != 0;
                } finally { _reply.recycle(); _data.recycle(); }
            }

            @Override
            public String getZenzLoadError() throws android.os.RemoteException {
                android.os.Parcel _data = android.os.Parcel.obtain();
                android.os.Parcel _reply = android.os.Parcel.obtain();
                try {
                    _data.writeInterfaceToken(DESCRIPTOR);
                    mRemote.transact(TRANSACTION_getZenzLoadError, _data, _reply, 0);
                    _reply.readException();
                    return _reply.readString();
                } finally { _reply.recycle(); _data.recycle(); }
            }

            @Override
            public void zenzConvert(String katakanaReading, String leftContext, ILlmCallback callback) throws android.os.RemoteException {
                android.os.Parcel _data = android.os.Parcel.obtain();
                android.os.Parcel _reply = android.os.Parcel.obtain();
                try {
                    _data.writeInterfaceToken(DESCRIPTOR);
                    _data.writeString(katakanaReading);
                    _data.writeString(leftContext);
                    _data.writeStrongBinder(callback != null ? callback.asBinder() : null);
                    mRemote.transact(TRANSACTION_zenzConvert, _data, _reply, 0);
                    _reply.readException();
                } finally { _reply.recycle(); _data.recycle(); }
            }

            @Override
            public void cancelZenzGeneration() throws android.os.RemoteException {
                android.os.Parcel _data = android.os.Parcel.obtain();
                android.os.Parcel _reply = android.os.Parcel.obtain();
                try {
                    _data.writeInterfaceToken(DESCRIPTOR);
                    mRemote.transact(TRANSACTION_cancelZenzGeneration, _data, _reply, 0);
                    _reply.readException();
                } finally { _reply.recycle(); _data.recycle(); }
            }
        }
    }
}
