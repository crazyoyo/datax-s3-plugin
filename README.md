# DataX S3 Plugin
### 本项目为DataX的S3插件，其功能类似https://help.aliyun.com/document_detail/308261.html.
### 下载DataX，下载S3插件：
* ``` git clone https://github.com/alibaba/DataX ``` 
* ``` git clone https://github.com/crazyoyo/DataXS3Plugin ``` 

### 编译S3Reader 和 S3Writer插件：
* ``` cp -r DataXS3Plugin/s3* DataX/ ```
* ``` cd DataX; mvn clean install -Dmaven.test.skip=true ```
* 注：首次编译可能会失败，但依赖的java库已经编译到本地，直接进入s3reader和s3writer目录重新编译即可。
* ``` cd DataX/s3reader; mvn clean install -Dmaven.test.skip=true ```
* ``` cd DataX/s3writer; mvn clean install -Dmaven.test.skip=true ```

### 创建DataX Job并执行数据迁移：
如下JoB示例为从S3迁移数据到本地文件：

``` 
python bin/datax.py job/s3TOcsv.json
``` 

``` 
{
  "core": {
    "transport": {
      "channel": {
        "speed": {
          "channel": 100
        }
      }
    }
  },
  "job": {
    "setting": {
      "speed": {
        "channel": 20
      }
    },
    "content": [
      {
        "reader": {
          "name": "s3reader",
          "parameter": {
	    "endpoint": "s3.ap-southeast-1.amazonaws.com",
	    "region": "ap-southeast-1",
            "accessId": "xxxxxxxxxxxxxxxxxxxxxxxxx",
            "accessKey": "xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx",
            "bucket": "test",
            "object": [
                    "out.csv"
            ],
            "column": [
              {
                "index": 0,
                "type": "string"
              },
              {
                "index": 1,
                "type": "string"
              },
              {
                "index": 2,
                "type": "string"
              },
              {
                "index": 3,
                "type": "long"
              },
              {
                "index": 4,
                "type": "string"
              },
              {
                "index": 5,
                "type": "string"
              },
              {
                "index": 6,
                "type": "string"
              },
              {
                "index": 7,
                "type": "long"
              },
              {
                "index": 8,
                "type": "string"
              },
              {
                "index": 9,
                "type": "date",
                "format": "yyyy/MM/dd"
              },
              {
                "index": 10,
                "type": "double"
              },
              {
                "index": 11,
                "type": "double"
              },
              {
                "index": 12,
                "type": "string"
              },
              {
                "index": 13,
                "type": "string"
              }
            ],
            "fieldDelimiter": ","
          }
        },

        "writer": {
          "name": "txtfilewriter",
          "parameter": {
            "path": "/tmp/",
            "fileName": "test",
            "writeMode": "truncate",
            "dateFormat": "yyyy/MM/dd"
          }
        }
      }
    ]
  }
}
```

