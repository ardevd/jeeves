"""Verify selected-install option parsing without JEB, Java or network access."""
import subprocess
import tempfile
import unittest
from pathlib import Path

HELPER=Path(__file__).resolve().parents[1]/'jeb-jvm-options.sh'

class InstalledOptionsTest(unittest.TestCase):
    def parse(self,contents):
        with tempfile.TemporaryDirectory(prefix='jeb options ') as folder:
            directory=Path(folder)
            if contents is not None:
                (directory/'jvmopt.txt').write_text(contents)
            result=subprocess.run(['/bin/bash','-c',
                'set -euo pipefail; source "$1"; load_jeb_jvm_options "$2"; '
                'for arg in ${JEB_JVM_OPTIONS[@]+"${JEB_JVM_OPTIONS[@]}"}; do printf "%s\\0" "$arg"; done',
                'test',str(HELPER),str(directory)],check=True,capture_output=True)
            return result.stdout.decode().split('\0')[:-1]
    def test_absent(self): self.assertEqual(self.parse(None),[])
    def test_empty(self): self.assertEqual(self.parse(' \n\t'),[])
    def test_vendor_space_delimited_format(self):
        self.assertEqual(self.parse('-Xss4M -Dfoo=bar\n\t-Dflag=true\r\n'),
                         ['-Xss4M','-Dfoo=bar','-Dflag=true'])
    def test_last_line_without_newline(self):
        self.assertEqual(self.parse('-Dlast=value'),['-Dlast=value'])
    def test_literal_not_shell_code(self):
        self.assertEqual(self.parse('-Dvalue=$(id) -Dother=`id` -Dglob=*'),
                         ['-Dvalue=$(id)','-Dother=`id`','-Dglob=*'])
    def test_quote_characters_follow_vendor_format(self):
        self.assertEqual(self.parse('-Dvalue="a b"'),['-Dvalue="a','b"'])

if __name__=='__main__': unittest.main()
