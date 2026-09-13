import { KRAny, KRRecord, KuiklyRenderBaseModule, KuiklyRenderCallback } from '@kuikly-open/render';

export class KRMyModule extends KuiklyRenderBaseModule {

  static readonly MODULE_NAME = "KRMyModule";

  syncMode(): boolean {
    return false;
  }

  call(method: string, params: KRAny, callback: KuiklyRenderCallback | null): KRAny {

    switch (method) {
      case 'myFun1':

        const json: Record<string, string> = JSON.parse(params as string);
        return json["data"];
      case 'myFun2':

        const array = params as Object[];
        const bytes = array[0] as Int8Array;
        const tmp1 = bytes[0];
        const tmp2 = bytes[1];
        bytes[0] = bytes[3];
        bytes[1] = bytes[2];
        bytes[2] = tmp2;
        bytes[3] = tmp1;
        return bytes;
      case 'myFun3':

        const json2: Record<string, string> = JSON.parse(params as string);
        const content = json2["data"];
        callback?.(`{"content": "${content}"}`);
        return null;
      case 'myFun4':

        const array2 = params as Object[];
        callback?.(array2[0] as string);
        return null;
      case 'aTestMethod':
        console.log("aTestMethod called")
        const x = new Int8Array([33, 34, 35]);
        const ret : KRRecord = {'a': x, 's': "hello"}
        callback?.(ret);
        return null

      default:
        return null;
    }
  }
}
