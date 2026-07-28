package android.net;

import android.os.Binder;
import android.os.IBinder;
import android.os.IInterface;
import android.os.RemoteException;

/**
 * Hidden API stub for IConnectivityManager.
 * <p>
 * At compile time this provides the type signatures needed by
 * {@link com.jizizr.signaldock.AppShell}.
 * At runtime the framework's real implementation (on the bootclasspath)
 * takes precedence — our stub is never loaded.
 * <p>
 * Copied from InstallerX-Revived (GPL-3.0).
 */
public interface IConnectivityManager extends IInterface {

    // Chains: FIREWALL_CHAIN_METERED = 1, FIREWALL_CHAIN_DOZABLE = 2,
    // FIREWALL_CHAIN_STANDBY = 3
    void setFirewallChainEnabled(int chain, boolean enable) throws RemoteException;

    // Rules: FIREWALL_RULE_DEFAULT = 0, FIREWALL_RULE_ALLOW = 1, FIREWALL_RULE_DENY
    // = 2
    void setUidFirewallRule(int chain, int uid, int rule) throws RemoteException;

    int getUidFirewallRule(int chain, int uid) throws RemoteException;

    abstract class Stub extends Binder implements IConnectivityManager {

        String DESCRIPTOR = "android.net.IConnectivityManager";

        public static IConnectivityManager asInterface(IBinder obj) {
            if (obj == null)
                return null;
            IInterface iin = obj.queryLocalInterface("android.net.IConnectivityManager");
            if (iin instanceof IConnectivityManager)
                return (IConnectivityManager) iin;
            // Proxy: used only if bootclasspath class is unavailable (should never happen
            // on device)
            return new IConnectivityManager() {
                @Override
                public IBinder asBinder() {
                    return obj;
                }

                @Override
                public void setFirewallChainEnabled(int chain, boolean enable) throws RemoteException {
                    android.os.Parcel _data = android.os.Parcel.obtain();
                    android.os.Parcel _reply = android.os.Parcel.obtain();
                    try {
                        _data.writeInterfaceToken("android.net.IConnectivityManager");
                        _data.writeInt(chain);
                        _data.writeInt(enable ? 1 : 0);
                        obj.transact(IBinder.FIRST_CALL_TRANSACTION, _data, _reply, 0);
                        _reply.readException();
                    } finally {
                        _reply.recycle();
                        _data.recycle();
                    }
                }

                @Override
                public void setUidFirewallRule(int chain, int uid, int rule) throws RemoteException {
                    android.os.Parcel _data = android.os.Parcel.obtain();
                    android.os.Parcel _reply = android.os.Parcel.obtain();
                    try {
                        _data.writeInterfaceToken("android.net.IConnectivityManager");
                        _data.writeInt(chain);
                        _data.writeInt(uid);
                        _data.writeInt(rule);
                        obj.transact(IBinder.FIRST_CALL_TRANSACTION + 1, _data, _reply, 0);
                        _reply.readException();
                    } finally {
                        _reply.recycle();
                        _data.recycle();
                    }
                }

                @Override
                public int getUidFirewallRule(int chain, int uid) throws RemoteException {
                    android.os.Parcel _data = android.os.Parcel.obtain();
                    android.os.Parcel _reply = android.os.Parcel.obtain();
                    try {
                        _data.writeInterfaceToken("android.net.IConnectivityManager");
                        _data.writeInt(chain);
                        _data.writeInt(uid);
                        obj.transact(IBinder.FIRST_CALL_TRANSACTION + 2, _data, _reply, 0);
                        _reply.readException();
                        return _reply.readInt();
                    } finally {
                        _reply.recycle();
                        _data.recycle();
                    }
                }
            };
        }
    }
}
