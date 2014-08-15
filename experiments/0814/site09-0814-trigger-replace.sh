ant clean-all build-all

ant hstore-prepare -Dproject="microexpftriggerstrig1" -Dhosts="localhost:0:0"
ant hstore-prepare -Dproject="microexpnoftriggerstrig1" -Dhosts="localhost:0:0"

python ./tools/autorunexp.py -p "microexpnoftriggerstrig1" -o "experiments/0814/microexpnoftriggerstrig1-1c-95-0814-site09-perc.txt" \
--txnthreshold 0.95 -e "experiments/0814/site09-0814-ftrigger-replace.txt" --winconfig "(site09) perc_compare" \
--threads 1 --rmin 10000 --rstep 10000 --finalrstep 100 --warmup 10000 --hstore --hscheduler --numruns 1 --perc_compare
python ./tools/autorunexp.py -p "microexpftriggerstrig1" -o "experiments/0814/microexpftriggerstrig1-1c-90-0814-site09-perc.txt" \
--txnthreshold 0.95 -e "experiments/0814/site09-0814-ftrigger-replace.txt" --winconfig "(site09) perc_compare" \
--threads 1 --rmin 10000 --rstep 10000 --finalrstep 100 --warmup 10000 --hscheduler --numruns 1 --perc_compare

for i in `seq 2 10`;
do
ant hstore-prepare -Dproject="microexpftriggerstrig${i}" -Dhosts="localhost:0:0"
ant hstore-prepare -Dproject="microexpnoftriggerstrig${i}" -Dhosts="localhost:0:0"

python ./tools/autorunexp.py -p "microexpnoftriggerstrig${i}" -o "experiments/0814/microexpnoftriggerstrig${i}-1c-95-0814-site09-perc.txt" \
--txnthreshold 0.95 -e "experiments/0814/site09-0814-ftrigger-replace.txt" --winconfig "(site09) perc_compare" \
--threads 1 --rmin 1000 --rstep 1000 --finalrstep 100 --warmup 10000 --hstore --hscheduler --numruns 1 --perc_compare
python ./tools/autorunexp.py -p "microexpftriggerstrig${i}" -o "experiments/0814/microexpftriggerstrig${i}-1c-90-0814-site09-perc.txt" \
--txnthreshold 0.95 -e "experiments/0814/site09-0814-ftrigger-replace.txt" --winconfig "(site09) perc_compare" \
--threads 1 --rmin 1000 --rstep 1000 --finalrstep 100 --warmup 10000 --hscheduler --numruns 1 --perc_compare
done

python ./tools/autorunexp.py -p "microexpnoftriggerstrig1" -o "experiments/0814/microexpnoftriggerstrig1-1c-95-0814-site09-perc.txt" \
--txnthreshold 0.95 -e "experiments/0814/site09-0814-ftrigger-replace.txt" --winconfig "(site09) perc_compare" \
--threads 1 --rmin 10000 --rstep 10000 --finalrstep 100 --warmup 10000 --hstore --hscheduler --numruns 1 --perc_compare --log
python ./tools/autorunexp.py -p "microexpftriggerstrig1" -o "experiments/0814/microexpftriggerstrig1-1c-90-0814-site09-perc.txt" \
--txnthreshold 0.95 -e "experiments/0814/site09-0814-ftrigger-replace.txt" --winconfig "(site09) perc_compare" \
--threads 1 --rmin 10000 --rstep 10000 --finalrstep 100 --warmup 10000 --hscheduler --numruns 1 --perc_compare --log
python ./tools/autorunexp.py -p "microexpftriggerstrig1" -o "experiments/0814/microexpftriggerstrig1-1c-90-0814-site09-perc.txt" \
--txnthreshold 0.95 -e "experiments/0814/site09-0814-ftrigger-replace.txt" --winconfig "(site09) perc_compare" \
--threads 1 --rmin 10000 --rstep 10000 --finalrstep 100 --warmup 10000 --hscheduler --numruns 1 --perc_compare --log --weakrecovery_off

for i in `seq 2 10`;
do
python ./tools/autorunexp.py -p "microexpnoftriggerstrig${i}" -o "experiments/0814/microexpnoftriggerstrig${i}-1c-95-0814-site09-perc.txt" \
--txnthreshold 0.95 -e "experiments/0814/site09-0814-ftrigger-replace.txt" --winconfig "(site09) perc_compare" \
--threads 1 --rmin 1000 --rstep 1000 --finalrstep 100 --warmup 10000 --hstore --hscheduler --numruns 1 --perc_compare --log
python ./tools/autorunexp.py -p "microexpftriggerstrig${i}" -o "experiments/0814/microexpftriggerstrig${i}-1c-90-0814-site09-perc.txt" \
--txnthreshold 0.95 -e "experiments/0814/site09-0814-ftrigger-replace.txt" --winconfig "(site09) perc_compare" \
--threads 1 --rmin 1000 --rstep 1000 --finalrstep 100 --warmup 10000 --hscheduler --numruns 1 --perc_compare --log
python ./tools/autorunexp.py -p "microexpftriggerstrig${i}" -o "experiments/0814/microexpftriggerstrig${i}-1c-90-0814-site09-perc.txt" \
--txnthreshold 0.95 -e "experiments/0814/site09-0814-ftrigger-replace.txt" --winconfig "(site09) perc_compare" \
--threads 1 --rmin 1000 --rstep 1000 --finalrstep 100 --warmup 10000 --hscheduler --numruns 1 --perc_compare --log --weakrecovery_off
done