import re
import os
import json
import time
import tempfile
import requests
from lxml import etree
from abc import abstractmethod, ABCMeta
from importlib.machinery import SourceFileLoader

# 宿主桥接：FongMi/PeekPro 走 chaquopy 与 catvod Proxy；PC 纯 CPython 下二者都不存在，
# 这里必须容错，否则整份 base.spider 导入失败 → 所有 py 源直接加载不起来。
try:
    from com.github.catvod import Proxy
except Exception:
    Proxy = None
try:
    from com.chaquo.python import Python
except Exception:
    Python = None

_CACHE = {}
_CACHE_FILE = None
_TTL_KEY = 'expiresAt'


def _cache_dir():
    base = None
    try:
        base = Python.getPlatform().getApplication().getCacheDir().getAbsolutePath()
    except Exception:
        base = None
    if not base:
        base = os.path.join(tempfile.gettempdir(), 'spider-cache')
    try:
        os.makedirs(base, exist_ok=True)
    except Exception:
        base = tempfile.gettempdir()
    return base


def _cache_path():
    global _CACHE_FILE
    if _CACHE_FILE:
        return _CACHE_FILE
    _CACHE_FILE = os.path.join(_cache_dir(), 'spider_cache.json')
    return _CACHE_FILE


def _cache_load():
    """宿主缓存: 内存 → 落盘 JSON。宿主 Proxy 不可用时这是唯一存储。"""
    if _CACHE:
        return _CACHE
    try:
        with open(_cache_path(), 'r', encoding='utf-8') as handle:
            data = json.load(handle)
        if isinstance(data, dict):
            _CACHE.update(data)
    except Exception:
        pass
    return _CACHE


def _cache_save():
    try:
        with open(_cache_path(), 'w', encoding='utf-8') as handle:
            json.dump(_CACHE, handle, ensure_ascii=False)
    except Exception:
        pass


def _proxy_port():
    """宿主 Proxy 端口; 未启动(-1/0)时返回 0, 走本地缓存。"""
    try:
        port = int(Proxy.getPort()) if Proxy is not None else 0
        return port if port > 0 else 0
    except Exception:
        return 0


def _decode_cache(value):
    if value is None:
        return None
    if isinstance(value, (dict, list)):
        data = value
    else:
        text = str(value)
        if not text:
            return None
        if not ((text.startswith('{') and text.endswith('}')) or (text.startswith('[') and text.endswith(']'))):
            return text
        try:
            data = json.loads(text)
        except Exception:
            return text
    if isinstance(data, dict) and _TTL_KEY in data:
        try:
            if float(data[_TTL_KEY]) < time.time():
                return None
        except Exception:
            pass
    return data


class Spider(metaclass=ABCMeta):
    _instance = None

    def __init__(self):
            self.extend = ''

    def __new__(cls, *args, **kwargs):
        if cls._instance:
            return cls._instance
        else:
            cls._instance = super().__new__(cls)
            return cls._instance

    @abstractmethod
    def init(self, extend=""):
        pass

    def homeContent(self, filter):
        pass

    def homeVideoContent(self):
        pass

    def categoryContent(self, tid, pg, filter, extend):
        pass

    def detailContent(self, ids):
        pass

    def searchContent(self, key, quick, pg="1"):
        pass

    def playerContent(self, flag, id, vipFlags):
        pass

    def liveContent(self, url):
        pass

    def localProxy(self, param):
        pass

    def isVideoFormat(self, url):
        pass

    def manualVideoCheck(self):
        pass

    def action(self, action):
        pass

    def destroy(self):
        pass

    def getName(self):
        pass

    def getDependence(self):
        return []

    def loadSpider(self, name):
        return self.loadModule(name).Spider()

    def loadModule(self, name):
        path = os.path.join(_cache_dir(), 'py', f'{name}.py')
        return SourceFileLoader(name, path).load_module()

    def regStr(self, reg, src, group=1):
        m = re.search(reg, src)
        src = ''
        if m:
            src = m.group(group)
        return src

    def removeHtmlTags(self, src):
        clean = re.compile('<.*?>')
        return re.sub(clean, '', src)

    def cleanText(self, src):
        clean = re.sub('[\U0001F600-\U0001F64F\U0001F300-\U0001F5FF\U0001F680-\U0001F6FF\U0001F1E0-\U0001F1FF]', '', src)
        return clean

    def fetch(self, url, params=None, cookies=None, headers=None, timeout=5, verify=True, stream=False, allow_redirects = True):
        rsp = requests.get(url, params=params, cookies=cookies, headers=headers, timeout=timeout, verify=verify, stream=stream, allow_redirects=allow_redirects)
        rsp.encoding = 'utf-8'
        return rsp

    def post(self, url, params=None, data=None, json=None, cookies=None, headers=None, timeout=5, verify=True, stream=False, allow_redirects = True):
        rsp = requests.post(url, params=params, data=data, json=json, cookies=cookies, headers=headers, timeout=timeout, verify=verify, stream=stream, allow_redirects=allow_redirects)
        rsp.encoding = 'utf-8'
        return rsp

    def html(self, content):
        return etree.HTML(content)

    def str2json(str):
        return json.loads(str)

    def json2str(str):
        return json.dumps(str, ensure_ascii=False)
    
    def getProxyUrl(self, local=True):
        try:
            return f'{Proxy.getUrl(local)}?do=py'
        except Exception:
            return ''

    def log(self, msg):
        if isinstance(msg, dict) or isinstance(msg, list):
            print(json.dumps(msg, ensure_ascii=False))
        else:
            print(f'{msg}')

    def getCache(self, key):
        """宿主 /cache 优先(与 FongMi 一致), 宿主没起 Proxy 时回落到本地文件缓存。

        关键: 原实现直接打 http://127.0.0.1:{Proxy.getPort()}/cache, 宿主未启动 Proxy 时
        getPort() 为 -1 → requests 抛异常 → 源里 `except: self.host = ''` 静默失败 →
        homeContent 返回 None → 测活显示「返回非 JSON」。这是大批 py 源假失败的根因。
        """
        port = _proxy_port()
        if port:
            try:
                text = self.fetch(f'http://127.0.0.1:{port}/cache?do=get&key={key}', timeout=5).text
                value = _decode_cache(text)
                if value is not None:
                    return value
            except Exception:
                pass
        return _decode_cache(_cache_load().get(key))

    def setCache(self, key, value):
        if isinstance(value, (int, float)):
            value = str(value)
        if isinstance(value, (dict, list)):
            value = json.dumps(value, ensure_ascii=False)
        text = '' if value is None else str(value)
        _cache_load()[key] = text
        _cache_save()
        port = _proxy_port()
        if port:
            try:
                self.post(f'http://127.0.0.1:{port}/cache?do=set&key={key}', data={"value": text}, timeout=5)
            except Exception:
                pass
        return 'succeed'

    def delCache(self, key):
        _cache_load().pop(key, None)
        _cache_save()
        port = _proxy_port()
        if port:
            try:
                self.fetch(f'http://127.0.0.1:{port}/cache?do=del&key={key}', timeout=5)
            except Exception:
                pass
        return 'succeed'
