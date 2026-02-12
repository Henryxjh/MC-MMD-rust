package com.shiroha.mmdskin.util;

import java.io.IOException;

public class AssetsUtil {

    public static String getAssetsAsString(String assetsPath) {
        var stream = AssetsUtil.class.getClassLoader()
                .getResourceAsStream(String.format("%s%s", "assets/mmdskin/", assetsPath));
        if (stream == null) {
            return null;
        }

        try {
            return new String(stream.readAllBytes());
        } catch (IOException e) {
            return null;
        }
    }

}
