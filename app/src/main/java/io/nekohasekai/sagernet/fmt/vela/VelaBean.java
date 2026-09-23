package io.nekohasekai.sagernet.fmt.vela;

import androidx.annotation.NonNull;

import com.esotericsoftware.kryo.io.ByteBufferInput;
import com.esotericsoftware.kryo.io.ByteBufferOutput;

import org.jetbrains.annotations.NotNull;

import io.nekohasekai.sagernet.fmt.AbstractBean;
import io.nekohasekai.sagernet.fmt.KryoConverters;

public class VelaBean extends AbstractBean {
    public String clientPrivateKey;
    public String serverPublicKey;

    @Override
    public void initializeDefaultValues() {
        if (serverPort == null) serverPort = 8443;
        super.initializeDefaultValues();
        if (clientPrivateKey == null) clientPrivateKey = "";
        if (serverPublicKey == null) serverPublicKey = "";
    }

    public void validate() {
        if (serverAddress == null || serverAddress.isBlank()) {
            throw new IllegalArgumentException("Vela server address is required");
        }
        if (serverPort == null || serverPort < 1 || serverPort > 65535) {
            throw new IllegalArgumentException("Vela server port must be 1..65535");
        }
        validateKey(clientPrivateKey, "client private key");
        validateKey(serverPublicKey, "server public key");
    }

    private static void validateKey(String value, String label) {
        if (value == null || !value.matches("(?i)[0-9a-f]{64}")) {
            throw new IllegalArgumentException("Vela " + label + " must be 64 hexadecimal characters");
        }
    }

    @NonNull
    @Override
    public String toString() {
        return "VelaBean{" +
                "serverAddress='" + serverAddress + '\'' +
                ", serverPort=" + serverPort +
                ", name='" + name + '\'' +
                ", clientPrivateKey=<redacted>" +
                ", serverPublicKey=<omitted>" +
                '}';
    }

    @Override
    public void serialize(ByteBufferOutput output) {
        output.writeInt(1);
        super.serialize(output);
        output.writeString(clientPrivateKey);
        output.writeString(serverPublicKey);
    }

    @Override
    public void deserialize(ByteBufferInput input) {
        int version = input.readInt();
        super.deserialize(input);
        if (version >= 1) {
            clientPrivateKey = input.readString();
            serverPublicKey = input.readString();
        }
    }

    @NotNull
    @Override
    public VelaBean clone() {
        return KryoConverters.deserialize(new VelaBean(), KryoConverters.serialize(this));
    }

    public static final Creator<VelaBean> CREATOR = new CREATOR<VelaBean>() {
        @NonNull
        @Override
        public VelaBean newInstance() {
            return new VelaBean();
        }

        @Override
        public VelaBean[] newArray(int size) {
            return new VelaBean[size];
        }
    };
}
