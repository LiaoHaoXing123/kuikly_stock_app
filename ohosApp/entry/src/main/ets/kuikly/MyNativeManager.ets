import { KuiklyNativeManager } from '@kuikly-open/render';
import Napi from 'libentry.so';

class MyNativeManager extends KuiklyNativeManager {
  protected loadNative(): number {

    return Napi.initKuikly();
  }
}

const globalNativeManager = new MyNativeManager();

export default globalNativeManager;
