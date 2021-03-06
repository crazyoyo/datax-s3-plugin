package com.alibaba.datax.plugin.writer.s3writer;

import com.alibaba.datax.common.element.Record;
import com.alibaba.datax.common.exception.DataXException;
import com.alibaba.datax.common.plugin.RecordReceiver;
import com.alibaba.datax.common.spi.Writer;
import com.alibaba.datax.common.util.Configuration;
import com.alibaba.datax.common.util.RetryUtil;
import com.alibaba.datax.plugin.unstructuredstorage.writer.TextCsvWriterManager;
import com.alibaba.datax.plugin.unstructuredstorage.writer.UnstructuredStorageWriterUtil;
import com.alibaba.datax.plugin.unstructuredstorage.writer.UnstructuredWriter;
import com.alibaba.datax.plugin.writer.s3writer.util.S3Util;
import com.amazonaws.services.s3.AmazonS3;
import com.amazonaws.services.s3.model.*;
import org.apache.commons.io.IOUtils;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.StringWriter;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.Callable;

/**
 * Created by chochen on 2021/5/31.
 */
public class S3Writer extends Writer {
    public static class Job extends Writer.Job {
        private static final Logger LOG = LoggerFactory.getLogger(Job.class);

        private Configuration writerSliceConfig = null;
        private AmazonS3 s3Client = null;

        @Override
        public void init() {
            this.writerSliceConfig = this.getPluginJobConf();
            this.validateParameter();
            this.s3Client = S3Util.initS3Client(this.writerSliceConfig);
        }

        private void validateParameter() {
            this.writerSliceConfig.getNecessaryValue(Key.REGION,
                    S3WriterErrorCode.REQUIRED_VALUE);
            this.writerSliceConfig.getNecessaryValue(Key.ACCESSID,
                    S3WriterErrorCode.REQUIRED_VALUE);
            this.writerSliceConfig.getNecessaryValue(Key.ACCESSKEY,
                    S3WriterErrorCode.REQUIRED_VALUE);
            this.writerSliceConfig.getNecessaryValue(Key.BUCKET,
                    S3WriterErrorCode.REQUIRED_VALUE);
            this.writerSliceConfig.getNecessaryValue(Key.OBJECT,
                    S3WriterErrorCode.REQUIRED_VALUE);
            // warn: do not support compress!!
            String compress = this.writerSliceConfig
                    .getString(com.alibaba.datax.plugin.unstructuredstorage.writer.Key.COMPRESS);
            if (StringUtils.isNotBlank(compress)) {
                String errorMessage = String.format("?????????????????????, ??????????????????[%s]????????????", compress);
                LOG.error(errorMessage);
                throw DataXException.asDataXException(S3WriterErrorCode.ILLEGAL_VALUE, errorMessage);
            }
            UnstructuredStorageWriterUtil.validateParameter(this.writerSliceConfig);

        }

        @Override
        public void prepare() {
            LOG.info("begin do prepare...");
            String bucket = this.writerSliceConfig.getString(Key.BUCKET);
            String object = this.writerSliceConfig.getString(Key.OBJECT);
            String writeMode = this.writerSliceConfig
                    .getString(com.alibaba.datax.plugin.unstructuredstorage.writer.Key.WRITE_MODE);
            // warn: bucket is not exists, create it
            try {
                // warn: do not create bucket for user
                if (!this.s3Client.doesBucketExist(bucket)) {
                    String errorMessage = String.format(
                            "????????????bucket [%s] ?????????, ???????????????????????????.", bucket);
                    LOG.error(errorMessage);
                    throw DataXException.asDataXException(S3WriterErrorCode.ILLEGAL_VALUE, errorMessage);
                }
                LOG.info(String.format("access control details [%s].",
                        this.s3Client.getBucketAcl(bucket).toString()));

                // truncate option handler
                if ("truncate".equals(writeMode)) {
                    LOG.info(String.format("??????????????????writeMode truncate, ???????????? [%s] ????????? [%s] ?????????Object",
                                    bucket, object));
                    // warn: ????????????????????????Bucket??????Object????????????100??????????????????100???Object
                    while (true) {
                        LOG.info("list objects with listObject(bucket, object)");
                        ListObjectsV2Result result = s3Client.listObjectsV2(bucket, object);
                        List<S3ObjectSummary> objectSummarys = result.getObjectSummaries();
                        for (S3ObjectSummary objectSummary : objectSummarys) {
                            LOG.info(String.format("delete s3 object [%s].", objectSummary.getKey()));
                            this.s3Client.deleteObject(bucket, objectSummary.getKey());
                        }
                        if (objectSummarys.isEmpty()) {
                            break;
                        }
                    }
                } else if ("append".equals(writeMode)) {
                    LOG.info(String
                            .format("??????????????????writeMode append, ???????????????????????????, ????????????Bucket [%s] ???, ????????????Object????????????  [%s]",
                                    bucket, object));
                } else if ("nonConflict".equals(writeMode)) {
                    LOG.info(String.format("??????????????????writeMode nonConflict, ????????????Bucket [%s] ????????? [%s] ???????????????Object",
                                    bucket, object));
                    ListObjectsV2Result result = s3Client.listObjectsV2(bucket, object);
                    if (0 < result.getObjectSummaries().size()) {
                        StringBuilder objectKeys = new StringBuilder();
                        objectKeys.append("[ ");
                        for (S3ObjectSummary s3ObjectSummary : result.getObjectSummaries()) {
                            objectKeys.append(s3ObjectSummary.getKey() + " ,");
                        }
                        objectKeys.append(" ]");
                        LOG.info(String.format(
                                "object with prefix [%s] details: %s", object,
                                objectKeys));
                        throw DataXException.asDataXException(
                                        S3WriterErrorCode.ILLEGAL_VALUE,
                                        String.format("????????????Bucket: [%s] ???????????????Object????????? [%s].",
                                                bucket, object));
                    }
                }
            } catch (Exception e) {
                throw DataXException.asDataXException(S3WriterErrorCode.S3_COMM_ERROR, e.getMessage());
            }
        }

        @Override
        public void post() {

        }

        @Override
        public void destroy() {

        }

        @Override
        public List<Configuration> split(int mandatoryNumber) {
            LOG.info("begin do split...");
            List<Configuration> writerSplitConfigs = new ArrayList<Configuration>();
            String object = this.writerSliceConfig.getString(Key.OBJECT);
            String bucket = this.writerSliceConfig.getString(Key.BUCKET);

            Set<String> allObjects = new HashSet<String>();
            try {
                List<S3ObjectSummary> s3Objectlisting = this.s3Client.listObjects(bucket).getObjectSummaries();
                for (S3ObjectSummary objectSummary : s3Objectlisting) {
                    allObjects.add(objectSummary.getKey());
                }
            } catch (Exception e) {
                throw DataXException.asDataXException(
                        S3WriterErrorCode.S3_COMM_ERROR, e.getMessage());
            }

            String objectSuffix;
            for (int i = 0; i < mandatoryNumber; i++) {
                // handle same object name
                Configuration splitedTaskConfig = this.writerSliceConfig.clone();

                String fullObjectName = null;
                objectSuffix = StringUtils.replace(UUID.randomUUID().toString(), "-", "");
                fullObjectName = String.format("%s__%s", object, objectSuffix);
                while (allObjects.contains(fullObjectName)) {
                    objectSuffix = StringUtils.replace(UUID.randomUUID().toString(), "-", "");
                    fullObjectName = String.format("%s__%s", object, objectSuffix);
                }
                allObjects.add(fullObjectName);
                splitedTaskConfig.set(Key.OBJECT, fullObjectName);
                LOG.info(String.format("splited write object name:[%s]", fullObjectName));

                writerSplitConfigs.add(splitedTaskConfig);
            }
            LOG.info("end do split.");
            return writerSplitConfigs;
        }
    }

    public static class Task extends Writer.Task {
        private static final Logger LOG = LoggerFactory.getLogger(Task.class);

        private AmazonS3 s3Client;
        private Configuration writerSliceConfig;
        private String bucket;
        private String object;
        private String nullFormat;
        private String encoding;
        private char fieldDelimiter;
        private String dateFormat;
        private DateFormat dateParse;
        private String fileFormat;
        private List<String> header;
        private Long maxFileSize;// MB
        private String suffix;

        @Override
        public void init() {
            this.writerSliceConfig = this.getPluginJobConf();
            this.s3Client = S3Util.initS3Client(this.writerSliceConfig);
            this.bucket = this.writerSliceConfig.getString(Key.BUCKET);
            this.object = this.writerSliceConfig.getString(Key.OBJECT);
            this.nullFormat = this.writerSliceConfig
                    .getString(com.alibaba.datax.plugin.unstructuredstorage.writer.Key.NULL_FORMAT);
            this.dateFormat = this.writerSliceConfig
                    .getString(com.alibaba.datax.plugin.unstructuredstorage.writer.Key.DATE_FORMAT,
                            null);
            if (StringUtils.isNotBlank(this.dateFormat)) {
                this.dateParse = new SimpleDateFormat(dateFormat);
            }
            this.encoding = this.writerSliceConfig.getString(
                            com.alibaba.datax.plugin.unstructuredstorage.writer.Key.ENCODING,
                            com.alibaba.datax.plugin.unstructuredstorage.writer.Constant.DEFAULT_ENCODING);
            this.fieldDelimiter = this.writerSliceConfig.getChar(
                            com.alibaba.datax.plugin.unstructuredstorage.writer.Key.FIELD_DELIMITER,
                            com.alibaba.datax.plugin.unstructuredstorage.writer.Constant.DEFAULT_FIELD_DELIMITER);
            this.fileFormat = this.writerSliceConfig.getString(
                            com.alibaba.datax.plugin.unstructuredstorage.writer.Key.FILE_FORMAT,
                            com.alibaba.datax.plugin.unstructuredstorage.writer.Constant.FILE_FORMAT_TEXT);
            this.header = this.writerSliceConfig.getList(
                            com.alibaba.datax.plugin.unstructuredstorage.writer.Key.HEADER,
                            null, String.class);
            this.maxFileSize = this.writerSliceConfig.getLong(
                            com.alibaba.datax.plugin.unstructuredstorage.writer.Key.MAX_FILE_SIZE,
                            com.alibaba.datax.plugin.unstructuredstorage.writer.Constant.MAX_FILE_SIZE);
            this.suffix = this.writerSliceConfig.getString(
                            com.alibaba.datax.plugin.unstructuredstorage.writer.Key.SUFFIX,
                            com.alibaba.datax.plugin.unstructuredstorage.writer.Constant.DEFAULT_SUFFIX);
            this.suffix = this.suffix.trim();// warn: need trim
        }

        @Override
        public void startWrite(RecordReceiver lineReceiver) {
            // ???????????????????????????
            final long partSize = 1024 * 1024 * 10L;
            long numberCacul = (this.maxFileSize * 1024 * 1024L) / partSize;
            final long maxPartNumber = numberCacul >= 1 ? numberCacul : 1;
            int objectRollingNumber = 0;
            //warn: may be StringBuffer->StringBuilder
            StringWriter sw = new StringWriter();
            StringBuffer sb = sw.getBuffer();
            UnstructuredWriter unstructuredWriter = TextCsvWriterManager
                    .produceUnstructuredWriter(this.fileFormat,
                            this.fieldDelimiter, sw);
            Record record = null;

            LOG.info(String.format(
                    "begin do write, each object maxFileSize: [%s]MB...",
                    maxPartNumber * 10));
            String currentObject = this.object;
            InitiateMultipartUploadRequest currentInitiateMultipartUploadRequest = null;
            InitiateMultipartUploadResult currentInitiateMultipartUploadResult = null;
            boolean gotData = false;
            List<PartETag> currentPartETags = null;
            // to do:
            // ????????????currentPartNumber???????????????????????????InitiateMultipartUploadRequest????????????currentPartNumber???????????????
            int currentPartNumber = 1;
            try {
                // warn
                boolean needInitMultipartTransform = true;
                while ((record = lineReceiver.getFromReader()) != null) {
                    gotData = true;
                    // init:begin new multipart upload
                    if (needInitMultipartTransform) {
                        if (objectRollingNumber == 0) {
                            if (StringUtils.isBlank(this.suffix)) {
                                currentObject = this.object;
                            } else {
                                currentObject = String.format("%s%s", this.object, this.suffix);
                            }
                        } else {
                            // currentObject is like(no suffix)
                            // myfile__9b886b70fbef11e59a3600163e00068c_1
                            if (StringUtils.isBlank(this.suffix)) {
                                currentObject = String.format("%s_%s", this.object, objectRollingNumber);
                            } else {
                                // or with suffix
                                // myfile__9b886b70fbef11e59a3600163e00068c_1.csv
                                currentObject = String.format("%s_%s%s",
                                        this.object, objectRollingNumber,
                                        this.suffix);
                            }
                        }
                        objectRollingNumber++;
                        currentInitiateMultipartUploadRequest = new InitiateMultipartUploadRequest(this.bucket, currentObject);
                        currentInitiateMultipartUploadResult = s3Client.initiateMultipartUpload(currentInitiateMultipartUploadRequest);
                        currentPartETags = new ArrayList<PartETag>();
                        LOG.info(String.format("write to bucket: [%s] object: [%s] with s3 uploadId: [%s]",
                                        this.bucket, currentObject,
                                        currentInitiateMultipartUploadResult.getUploadId()));

                        // each object's header
                        if (null != this.header && !this.header.isEmpty()) {
                            unstructuredWriter.writeOneRecord(this.header);
                        }
                        // warn
                        needInitMultipartTransform = false;
                        currentPartNumber = 1;
                    }

                    // write: upload data to current object
                    UnstructuredStorageWriterUtil.transportOneRecord(record,
                            this.nullFormat, this.dateParse,
                            this.getTaskPluginCollector(), unstructuredWriter);

                    if (sb.length() >= partSize) {
                        this.uploadOnePart(sw, currentPartNumber,
                                currentInitiateMultipartUploadResult,
                                currentPartETags, currentObject);
                        currentPartNumber++;
                        sb.setLength(0);
                    }

                    // save: end current multipart upload
                    if (currentPartNumber > maxPartNumber) {
                        LOG.info(String.format("current object [%s] size > %s, complete current multipart upload and begin new one",
                                        currentObject, currentPartNumber * partSize));
                        CompleteMultipartUploadRequest currentCompleteMultipartUploadRequest = new CompleteMultipartUploadRequest(
                                this.bucket, currentObject,
                                currentInitiateMultipartUploadResult.getUploadId(), currentPartETags);
                        CompleteMultipartUploadResult currentCompleteMultipartUploadResult = s3Client.completeMultipartUpload(currentCompleteMultipartUploadRequest);
                        LOG.info(String.format(
                                "final object [%s] etag is:[%s]",
                                currentObject,
                                currentCompleteMultipartUploadResult.getETag()));
                        // warn
                        needInitMultipartTransform = true;
                    }
                }

                if (!gotData) {
                    LOG.info("Receive no data from the source.");
                    currentInitiateMultipartUploadRequest = new InitiateMultipartUploadRequest(this.bucket, currentObject);
                    currentInitiateMultipartUploadResult = s3Client.initiateMultipartUpload(currentInitiateMultipartUploadRequest);
                    currentPartETags = new ArrayList<PartETag>();
                    // each object's header
                    if (null != this.header && !this.header.isEmpty()) {
                        unstructuredWriter.writeOneRecord(this.header);
                    }
                }
                // warn: may be some data stall in sb
                if (0 < sb.length()) {
                    this.uploadOnePart(sw, currentPartNumber,
                            currentInitiateMultipartUploadResult,
                            currentPartETags, currentObject);
                }
                CompleteMultipartUploadRequest completeMultipartUploadRequest = new CompleteMultipartUploadRequest(
                        this.bucket, currentObject,
                        currentInitiateMultipartUploadResult.getUploadId(),
                        currentPartETags);
                CompleteMultipartUploadResult completeMultipartUploadResult = s3Client.completeMultipartUpload(completeMultipartUploadRequest);
                LOG.info(String.format("final object etag is:[%s]",
                        completeMultipartUploadResult.getETag()));
            } catch (IOException e) {
                // ?????????UnstructuredStorageWriterUtil.transportOneRecord????????????,header
                // ????????????????????????????????????
                throw DataXException.asDataXException(
                        S3WriterErrorCode.Write_OBJECT_ERROR, e.getMessage());
            } catch (Exception e) {
                throw DataXException.asDataXException(
                        S3WriterErrorCode.Write_OBJECT_ERROR, e.getMessage());
            }
            LOG.info("end do write");
        }

        /**
         * ???????????????UploadID????????????????????????????????????????????????????????????????????????????????????????????????????????????
         * ?????????????????????part???????????????????????????????????????S3???????????????????????????Part?????????????????????
         *
         * @throws Exception
         */
        private void uploadOnePart(
                final StringWriter sw,
                final int partNumber,
                final InitiateMultipartUploadResult initiateMultipartUploadResult,
                final List<PartETag> partETags, final String currentObject)
                throws Exception {
            final String encoding = this.encoding;
            final String bucket = this.bucket;
            final AmazonS3 s3Client = this.s3Client;
            RetryUtil.executeWithRetry(new Callable<Boolean>() {
                @Override
                public Boolean call() throws Exception {
                    byte[] byteArray = sw.toString().getBytes(encoding);
                    InputStream inputStream = new ByteArrayInputStream(
                            byteArray);
                    // ??????UploadPartRequest???????????????
                    UploadPartRequest uploadPartRequest = new UploadPartRequest();
                    uploadPartRequest.setBucketName(bucket);
                    uploadPartRequest.setKey(currentObject);
                    uploadPartRequest.setUploadId(initiateMultipartUploadResult.getUploadId());
                    uploadPartRequest.setInputStream(inputStream);
                    uploadPartRequest.setPartSize(byteArray.length);
                    uploadPartRequest.setPartNumber(partNumber);
                    UploadPartResult uploadPartResult = s3Client.uploadPart(uploadPartRequest);
                    partETags.add(uploadPartResult.getPartETag());
                    LOG.info(String.format("upload part [%s] size [%s] Byte has been completed.",
                                    partNumber, byteArray.length));
                    IOUtils.closeQuietly(inputStream);
                    return true;
                }
            }, 3, 1000L, false);
        }

        @Override
        public void prepare() {

        }

        @Override
        public void post() {

        }

        @Override
        public void destroy() {

        }
    }
}
