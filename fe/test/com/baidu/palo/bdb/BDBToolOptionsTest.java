package com.baidu.palo.bdb;

import com.baidu.palo.common.FeConstants;
import com.baidu.palo.journal.bdbje.BDBToolOptions;

import org.junit.Assert;
import org.junit.Test;

public class BDBToolOptionsTest {

    @Test
    public void test() {
        BDBToolOptions options = new BDBToolOptions(true, "", false, "", "", 0);
        Assert.assertFalse(options.hasFromKey());
        Assert.assertFalse(options.hasEndKey());
        Assert.assertEquals(FeConstants.meta_version, options.getMetaVersion());

        options = new BDBToolOptions(false, "12345", false, "12345", "12456", 35);
        Assert.assertTrue(options.hasFromKey());
        Assert.assertTrue(options.hasEndKey());
        Assert.assertNotSame(FeConstants.meta_version, options.getMetaVersion());
    }

}
