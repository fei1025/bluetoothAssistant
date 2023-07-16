package com.zzf.bluetoothsmp.entity;

import org.litepal.crud.LitePalSupport;

public class SystemInfoMapper  extends LitePalSupport {

    private String serviceSpp;
    private String clientSpp;

    public String getServiceSpp() {
        return serviceSpp;
    }

    public void setServiceSpp(String serviceSpp) {
        this.serviceSpp = serviceSpp;
    }

    public String getClientSpp() {
        return clientSpp;
    }

    public void setClientSpp(String clientSpp) {
        this.clientSpp = clientSpp;
    }
}
