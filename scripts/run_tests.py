#!/usr/bin/env python3
"""Roda TODOS os testes automatizados disponíveis neste computador e diz com clareza o que foi
executado e o que foi PULADO (e por quê). Uso: python scripts/run_tests.py"""
import collections
import subprocess
import sys
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]


def main() -> int:
    sys.path.insert(0, str(ROOT / 'tests'))
    suite = unittest.defaultTestLoader.discover(str(ROOT / 'tests'))
    result = unittest.TextTestRunner(verbosity=1).run(suite)
    ran = result.testsRun - len(result.skipped)
    print('\n================ RESUMO ================')
    print(f'Executados: {ran}  ·  Passaram: {ran - len(result.failures) - len(result.errors)}  ·  '
          f'Falharam: {len(result.failures) + len(result.errors)}  ·  Pulados: {len(result.skipped)}')
    reasons = collections.Counter(reason for _, reason in result.skipped)
    for reason, count in reasons.items():
        print(f'  PULADOS ({count}): {reason}')
    print('\n--- Compilação do app Android contra stubs do SDK ---')
    compiled = subprocess.run([sys.executable, str(ROOT / 'scripts' / 'check_android_java.py')])
    print('\nLembrete: nada aqui substitui testar no Windows/Samsung A14 de verdade '
          '(ver docs/RELATORIO_TECNICO.md).')
    return 0 if result.wasSuccessful() and compiled.returncode == 0 else 1


if __name__ == '__main__':
    sys.exit(main())
