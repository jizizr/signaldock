package android.content.pm;

import android.os.Binder;
import android.os.IBinder;
import android.os.IInterface;

public interface IPackageManager extends IInterface {
    void grantRuntimePermission(String packageName, String permissionName, int userId)
            throws android.os.RemoteException;

    String DESCRIPTOR = "android.content.pm.IPackageManager";

    abstract class Stub extends Binder implements IPackageManager {
        public static IPackageManager asInterface(IBinder obj) {
            if (obj == null)
                return null;
            IInterface iin = obj.queryLocalInterface(DESCRIPTOR);
            if (iin instanceof IPackageManager)
                return (IPackageManager) iin;
            // Proxy constructed by ShizukuBinderWrapper at runtime
            return new IPackageManager() {
                @Override
                public IBinder asBinder() {
                    return obj;
                }

                @Override
                public void grantRuntimePermission(String p, String perm, int uid)
                        throws android.os.RemoteException {
                    android.os.Parcel _data = android.os.Parcel.obtain();
                    android.os.Parcel _reply = android.os.Parcel.obtain();
                    try {
                        _data.writeInterfaceToken(DESCRIPTOR);
                        _data.writeString(p);
                        _data.writeString(perm);
                        _data.writeInt(uid);
                        obj.transact(Stub.TRANSACTION_grantRuntimePermission, _data, _reply, 0);
                        _reply.readException();
                    } finally {
                        _reply.recycle();
                        _data.recycle();
                    }
                }
            };
        }

        static final int TRANSACTION_grantRuntimePermission = IBinder.FIRST_CALL_TRANSACTION + 0;
    }
}
