// JNI cho libaom: nhận khung RGBA (từ ImageReader), đổi sang I420 BT.709, mã hoá AV1, trả gói nén.
#include <jni.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>
#include <stdio.h>
#include "aom/aom_encoder.h"
#include "aom/aomcx.h"

typedef struct Pkt { struct Pkt *next; int64_t pts; int key; size_t sz; uint8_t *data; } Pkt;

typedef struct {
  aom_codec_ctx_t codec;
  aom_image_t img;
  int w, h, ok;
  int64_t frameDurUs;
  Pkt *head, *tail;
  char err[160];
} Ctx;

static void push_pkt(Ctx *c, const aom_codec_cx_pkt_t *p) {
  Pkt *n = (Pkt *)calloc(1, sizeof(Pkt));
  if (!n) return;
  n->data = (uint8_t *)malloc(p->data.frame.sz);
  if (!n->data) { free(n); return; }
  memcpy(n->data, p->data.frame.buf, p->data.frame.sz);
  n->sz = p->data.frame.sz; n->pts = p->data.frame.pts;
  n->key = (p->data.frame.flags & AOM_FRAME_IS_KEY) ? 1 : 0;
  if (c->tail) c->tail->next = n; else c->head = n;
  c->tail = n;
}

static int drain(Ctx *c) {
  aom_codec_iter_t it = NULL; const aom_codec_cx_pkt_t *p; int n = 0;
  while ((p = aom_codec_get_cx_data(&c->codec, &it)) != NULL)
    if (p->kind == AOM_CODEC_CX_FRAME_PKT) { push_pkt(c, p); n++; }
  return n;
}

static void seterr(Ctx *c, const char *what) {
  const char *d = aom_codec_error_detail(&c->codec);
  snprintf(c->err, sizeof c->err, "%s: %s%s%s", what, aom_codec_error(&c->codec), d ? " - " : "", d ? d : "");
}

// RGBA (R,G,B,A byte order) -> I420, BT.709 giới hạn dải (16-235)
static void rgba_to_i420(const uint8_t *src, int stride, aom_image_t *im, int w, int h) {
  uint8_t *Y = im->planes[0], *U = im->planes[1], *V = im->planes[2];
  int ys = im->stride[0], us = im->stride[1], vs = im->stride[2];
  for (int y = 0; y < h; y++) {
    const uint8_t *s = src + (size_t)y * stride; uint8_t *d = Y + (size_t)y * ys;
    for (int x = 0; x < w; x++, s += 4) d[x] = (uint8_t)(((47 * s[0] + 157 * s[1] + 16 * s[2] + 128) >> 8) + 16);
  }
  int cw = (w + 1) >> 1, ch = (h + 1) >> 1;
  for (int cy = 0; cy < ch; cy++) {
    int y0 = cy * 2, y1 = (y0 + 1 < h) ? y0 + 1 : y0;
    const uint8_t *r0 = src + (size_t)y0 * stride, *r1 = src + (size_t)y1 * stride;
    uint8_t *du = U + (size_t)cy * us, *dv = V + (size_t)cy * vs;
    for (int cx = 0; cx < cw; cx++) {
      int x0 = cx * 2, x1 = (x0 + 1 < w) ? x0 + 1 : x0;
      const uint8_t *a = r0 + x0 * 4, *b = r0 + x1 * 4, *c = r1 + x0 * 4, *e = r1 + x1 * 4;
      int R = (a[0] + b[0] + c[0] + e[0] + 2) >> 2, G = (a[1] + b[1] + c[1] + e[1] + 2) >> 2, B = (a[2] + b[2] + c[2] + e[2] + 2) >> 2;
      int u = ((-26 * R - 86 * G + 112 * B + 128) >> 8) + 128, v = ((112 * R - 102 * G - 10 * B + 128) >> 8) + 128;
      du[cx] = (uint8_t)(u < 0 ? 0 : u > 255 ? 255 : u);
      dv[cx] = (uint8_t)(v < 0 ? 0 : v > 255 ? 255 : v);
    }
  }
}

// fast=1: usage REALTIME + CBR, không lookahead. fast=0: GOOD_QUALITY + VBR, lookahead 16.
JNIEXPORT jlong JNICALL Java_com_alex_videoconv_AomNative_nCreate(JNIEnv *env, jclass cls, jint w, jint h,
    jint fpsNum, jint fpsDen, jint bitrateKbps, jint cpuUsed, jint threads, jboolean fast) {
  Ctx *c = (Ctx *)calloc(1, sizeof(Ctx));
  if (!c) return 0;
  c->w = w; c->h = h;
  c->frameDurUs = fpsNum > 0 ? (int64_t)(1000000.0 * fpsDen / fpsNum) : 33333;
  unsigned usage = fast ? AOM_USAGE_REALTIME : AOM_USAGE_GOOD_QUALITY;
  aom_codec_enc_cfg_t cfg;
  if (aom_codec_enc_config_default(aom_codec_av1_cx(), &cfg, usage) != AOM_CODEC_OK) { free(c); return 0; }
  cfg.g_w = w; cfg.g_h = h;
  cfg.g_timebase.num = 1; cfg.g_timebase.den = 1000000;       // pts tính bằng micro giây
  cfg.g_threads = threads > 0 ? threads : 4;
  cfg.g_lag_in_frames = fast ? 0 : 16;
  cfg.rc_end_usage = fast ? AOM_CBR : AOM_VBR;
  cfg.rc_target_bitrate = bitrateKbps;
  cfg.kf_mode = AOM_KF_AUTO;
  cfg.kf_max_dist = 300;
  if (aom_codec_enc_init(&c->codec, aom_codec_av1_cx(), &cfg, 0) != AOM_CODEC_OK) { free(c); return 0; }
  aom_codec_control(&c->codec, AOME_SET_CPUUSED, cpuUsed);
  aom_codec_control(&c->codec, AV1E_SET_ROW_MT, 1);
  if (w >= 1440) aom_codec_control(&c->codec, AV1E_SET_TILE_COLUMNS, w >= 2880 ? 2 : 1);
  aom_codec_control(&c->codec, AV1E_SET_COLOR_PRIMARIES, 1);          // BT.709
  aom_codec_control(&c->codec, AV1E_SET_TRANSFER_CHARACTERISTICS, 1);
  aom_codec_control(&c->codec, AV1E_SET_MATRIX_COEFFICIENTS, 1);
  aom_codec_control(&c->codec, AV1E_SET_COLOR_RANGE, 0);              // limited
  if (!aom_img_alloc(&c->img, AOM_IMG_FMT_I420, w, h, 32)) { aom_codec_destroy(&c->codec); free(c); return 0; }
  c->ok = 1;
  return (jlong)(intptr_t)c;
}

// Trả về av1C (4 byte + sequence header OBU) để ghi vào MP4
JNIEXPORT jbyteArray JNICALL Java_com_alex_videoconv_AomNative_nAv1C(JNIEnv *env, jclass cls, jlong h) {
  Ctx *c = (Ctx *)(intptr_t)h;
  aom_fixed_buf_t *g = aom_codec_get_global_headers(&c->codec);
  if (!g || !g->buf || g->sz < 3) return NULL;
  const uint8_t *o = (const uint8_t *)g->buf; size_t n = g->sz, p = 1;
  if (o[0] & 4) p++;                                   // extension
  if (o[0] & 2) { while (p < n && (o[p] & 0x80)) p++; p++; }   // leb128 size
  int level = 8, tier = 0;                             // mặc định level 4.0
  if (p + 3 < n) {
    uint32_t bits = 0; int have = 0; size_t q = p;
    #define NEED(k) while (have < (k) && q < n) { bits = (bits << 8) | o[q++]; have += 8; }
    #define GET(k) ({ NEED(k) int v = (int)((bits >> (have - (k))) & ((1u << (k)) - 1)); have -= (k); v; })
    int prof = GET(3); (void)prof; GET(1); int reduced = GET(1);
    if (reduced) { level = GET(5); }
    else if (GET(1) == 0) { GET(1); GET(5); GET(12); level = GET(5); if (level > 7) tier = GET(1); }
  }
  int prof0 = (o[p] >> 5) & 7;
  uint8_t head[4] = { 0x81, (uint8_t)((prof0 << 5) | (level & 31)), (uint8_t)((tier << 7) | 0x0C), 0 };
  jbyteArray out = (*env)->NewByteArray(env, (jsize)(4 + n));
  (*env)->SetByteArrayRegion(env, out, 0, 4, (jbyte *)head);
  (*env)->SetByteArrayRegion(env, out, 4, (jsize)n, (jbyte *)o);
  free(g->buf); free(g);
  return out;
}

// Trả về số gói đã sẵn sàng, hoặc -1 nếu lỗi
JNIEXPORT jint JNICALL Java_com_alex_videoconv_AomNative_nEncode(JNIEnv *env, jclass cls, jlong h,
    jobject rgba, jint rowStride, jlong ptsUs, jboolean key) {
  Ctx *c = (Ctx *)(intptr_t)h;
  const uint8_t *src = (const uint8_t *)(*env)->GetDirectBufferAddress(env, rgba);
  if (!src) { snprintf(c->err, sizeof c->err, "buffer không phải direct"); return -1; }
  rgba_to_i420(src, rowStride, &c->img, c->w, c->h);
  if (aom_codec_encode(&c->codec, &c->img, ptsUs, (unsigned long)c->frameDurUs, key ? AOM_EFLAG_FORCE_KF : 0) != AOM_CODEC_OK) {
    seterr(c, "aom_codec_encode"); return -1;
  }
  return drain(c);
}

JNIEXPORT jint JNICALL Java_com_alex_videoconv_AomNative_nFlush(JNIEnv *env, jclass cls, jlong h) {
  Ctx *c = (Ctx *)(intptr_t)h; int total = 0;
  for (;;) {
    if (aom_codec_encode(&c->codec, NULL, 0, 0, 0) != AOM_CODEC_OK) { seterr(c, "flush"); return -1; }
    int n = drain(c); total += n;
    if (n == 0) break;
  }
  return total;
}

// Lấy 1 gói: [key(1)][pts(8, big-endian)][dữ liệu...]; null nếu hết
JNIEXPORT jbyteArray JNICALL Java_com_alex_videoconv_AomNative_nNext(JNIEnv *env, jclass cls, jlong h) {
  Ctx *c = (Ctx *)(intptr_t)h; Pkt *p = c->head;
  if (!p) return NULL;
  c->head = p->next; if (!c->head) c->tail = NULL;
  jbyteArray out = (*env)->NewByteArray(env, (jsize)(9 + p->sz));
  uint8_t hd[9]; hd[0] = (uint8_t)p->key;
  for (int i = 0; i < 8; i++) hd[1 + i] = (uint8_t)((uint64_t)p->pts >> (8 * (7 - i)));
  (*env)->SetByteArrayRegion(env, out, 0, 9, (jbyte *)hd);
  (*env)->SetByteArrayRegion(env, out, 9, (jsize)p->sz, (jbyte *)p->data);
  free(p->data); free(p);
  return out;
}

JNIEXPORT jstring JNICALL Java_com_alex_videoconv_AomNative_nError(JNIEnv *env, jclass cls, jlong h) {
  return (*env)->NewStringUTF(env, ((Ctx *)(intptr_t)h)->err);
}

JNIEXPORT void JNICALL Java_com_alex_videoconv_AomNative_nDestroy(JNIEnv *env, jclass cls, jlong h) {
  Ctx *c = (Ctx *)(intptr_t)h; if (!c) return;
  Pkt *p = c->head; while (p) { Pkt *n = p->next; free(p->data); free(p); p = n; }
  aom_img_free(&c->img); aom_codec_destroy(&c->codec); free(c);
}
