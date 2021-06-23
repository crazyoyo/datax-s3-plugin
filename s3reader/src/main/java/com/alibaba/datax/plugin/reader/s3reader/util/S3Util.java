package com.alibaba.datax.plugin.reader.s3reader.util;

import com.alibaba.datax.common.exception.DataXException;
import com.alibaba.datax.common.util.Configuration;
import com.alibaba.datax.plugin.reader.s3reader.Key;
import com.alibaba.datax.plugin.reader.s3reader.S3ReaderErrorCode;
import com.amazonaws.auth.AWSStaticCredentialsProvider;
import com.amazonaws.auth.BasicAWSCredentials;
import com.amazonaws.regions.Regions;
import com.amazonaws.services.s3.AmazonS3;
import com.amazonaws.services.s3.AmazonS3ClientBuilder;

/**
 * Created by chochen on 2021/5/31.
 */
public class S3Util {
    public static AmazonS3 initS3Client(Configuration conf) {
        String regionStr = conf.getString(Key.REGION);
        Regions region = Regions.fromName(regionStr);
        String accessId = conf.getString(Key.ACCESSID);
        String accessKey = conf.getString(Key.ACCESSKEY);

        AmazonS3 client = null;
        try {
            BasicAWSCredentials awsCreds = new BasicAWSCredentials(accessId, accessKey);
            client = AmazonS3ClientBuilder.standard().withCredentials(new AWSStaticCredentialsProvider(awsCreds)).withRegion(region).build();
        } catch (IllegalArgumentException e) {
            throw DataXException.asDataXException(
                    S3ReaderErrorCode.ILLEGAL_VALUE, e.getMessage());
        }
        return client;
    }
}
