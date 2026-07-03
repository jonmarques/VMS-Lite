package br.com.jonmarques.vmslite.library;
import com.sun.jna.Library;
import com.sun.jna.Native;

public interface Iphlpapi extends Library {

    Iphlpapi INSTANCE = Native.load("Iphlpapi", Iphlpapi.class);

    int SendARP(
            int DestIP,
            int SrcIP,
            byte[] pMacAddr,
            int[] PhyAddrLen
    );
}