import os
import numpy as np
from PIL import Image
import ai_edge_litert.interpreter as tflite

def test_model(model_path, target_size):
    print(f"\n--- Testing {os.path.basename(model_path)} ---")
    interp = tflite.Interpreter(model_path=model_path)
    interp.allocate_tensors()
    inp = interp.get_input_details()[0]
    out = interp.get_output_details()[0]
    print(f"Input: {inp['name']}, shape={inp['shape']}, dtype={inp['dtype']}")
    print(f"Output: {out['name']}, shape={out['shape']}, dtype={out['dtype']}")
    
    img1 = Image.open('test_faces/obama.jpg').convert('RGB').resize(target_size)
    arr1 = np.array(img1, dtype=np.float32) / 255.0
    batch1 = np.expand_dims(arr1, axis=0)
    
    interp.set_tensor(inp['index'], batch1)
    interp.invoke()
    emb1 = interp.get_tensor(out['index'])[0]
    emb1 = emb1 / np.linalg.norm(emb1)

    img2 = Image.open('test_faces/obama2.jpg').convert('RGB').resize(target_size)
    arr2 = np.array(img2, dtype=np.float32) / 255.0
    batch2 = np.expand_dims(arr2, axis=0)
    interp.set_tensor(inp['index'], batch2)
    interp.invoke()
    emb2 = interp.get_tensor(out['index'])[0]
    emb2 = emb2 / np.linalg.norm(emb2)

    img_b = Image.open('test_faces/biden.jpg').convert('RGB').resize(target_size)
    arr_b = np.array(img_b, dtype=np.float32) / 255.0
    batch_b = np.expand_dims(arr_b, axis=0)
    interp.set_tensor(inp['index'], batch_b)
    interp.invoke()
    emb_b = interp.get_tensor(out['index'])[0]
    emb_b = emb_b / np.linalg.norm(emb_b)

    sim_same = float(np.dot(emb1, emb2))
    sim_diff = float(np.dot(emb1, emb_b))
    print(f"Genuine Cosine (Obama 1 vs Obama 2): {sim_same:.4f}")
    print(f"Impostor Cosine (Obama 1 vs Biden): {sim_diff:.4f}")
    print(f"Separation Delta: {sim_same - sim_diff:.4f}")

if __name__ == '__main__':
    test_model('models_cache/cavaface.tflite', (112, 112))
    test_model('models_cache/facenet512.tflite', (160, 160))
